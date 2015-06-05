(ns puppetlabs.puppetdb.cli.benchmark
  "Benchmark suite

   This command-line utility will simulate catalog submission for a
   population. It requires that a separate, running instance of
   PuppetDB for it to submit catalogs to.

   Aspects of a population this tool currently models:

   * Number of nodes
   * Run interval
   * How often a host's catalog changes
   * A starting catalog

   We attempt to approximate a number of hosts submitting catalogs at
   the specified runinterval with the specified rate-of-churn in
   catalog content.

   The list of nodes is modeled in the tool as a set of Clojure
   agents, with one agent per host. Each agent has the following
   state:

       {:host    ;; the host's name
        :lastrun ;; when the host last sent a catalog
        :catalog ;; the last catalog sent}

   When a host needs to submit a new catalog, we determine if the new
   catalog should be different than the previous one (based on a
   user-specified threshold) and send the resulting catalog to
   PuppetDB.

   ### Main loop

   The main loop is written in the form of a wall-clock
   simulator. Each run through the main loop, we send each agent an
   `update` message with the current wall-clock. Each agent decides
   independently whether or not to submit a catalog during that clock
   tick."
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.catalog.utils :as catutils]
            [puppetlabs.trapperkeeper.logging :as logutils]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.cheshire :as json]
            [fs.core :as fs]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [clj-time.core :as time]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.random :refer [random-string random-bool]]
            [puppetlabs.puppetdb.archive :as archive]
            [slingshot.slingshot :refer [try+]]))

(def cli-description "Development-only benchmarking tool")

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception e
      (log/errorf "Error parsing %s; skipping" file))))

(defn error-if
  [pred msg x]
  (if (pred x)
    (log/error msg)
    x))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  [dir & [from-classpath?]]
  (let [target-files (if from-classpath?
                       (remove #(.isDirectory %) (file-seq (io/file (io/resource dir))))
                       (fs/glob (fs/file dir "*.json")))]
    (->> target-files
         (map try-load-file)
         (filterv (complement nil?))
         (error-if empty? (format "Supplied directory %s contains no usable data!" dir)))))

(def mutate-fns
  "Functions that randomly change a wire-catalog format"
  [catutils/add-random-resource-to-wire-catalog
   catutils/mod-resource-in-wire-catalog
   catutils/add-random-edge-to-wire-catalog
   catutils/swap-edge-targets-in-wire-catalog])

(defn rand-catalog-mutation
  "Grabs one of the mutate-fns randomly and returns it"
  [catalog]
  ((rand-nth mutate-fns) catalog))

(declare random-fact-value-vector)

(defn random-fact-value
  "Given a type, generate a random fact value"
  ([] (random-fact-value (rand-nth [:int :float :bool :string :vector])))
  ([kind]
   (case kind
     :int (rand-int 300)
     :float (rand)
     :bool (random-bool)
     :string (random-string 4)
     :vector (random-fact-value-vector))))

(defn random-fact-value-vector
  []
  (-> (rand-int 10)
      (repeatedly #(random-fact-value (rand-nth [:string :int :float :bool])))
      vec))

(defn random-structured-fact
  "Create a 'random' structured fact.
  Parameters are fact depth and number of child facts.  Depth 0 implies one child."
  ([]
   (random-structured-fact (rand-nth [0 1 2 3])
                           (rand-nth [1 2 3 4])))
  ([depth children]
     (if (zero? depth)
       {(random-string 10) (random-fact-value)}
       {(random-string 10) (zipmap (repeatedly children #(random-string 10))
                                   (repeatedly children #(random-structured-fact (rand-nth (range depth))
                                                                                 (rand-nth (range children)))))})))

(defn maybe-tweak-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
  percent of the time."
  [catalog rand-percentage]
  (if (< (rand 100) rand-percentage)
    (rand-catalog-mutation catalog)
    catalog))

(defn update-catalog [catalog]
  (assoc catalog "producer_timestamp" (time/now)))

(defn update-report-run-fields
  "configuration_version, start_time and end_time should always change
   on subsequent report submittions, this changes those fields to avoid
   computing the same hash again (causing constraint errors in the DB)"
  [report]
  (assoc report
         "configuration_version" (ks/uuid)
         "start_time" (time/now)
         "end_time" (time/now)
         "producer_timestamp" (time/now)))

(defn randomize-map-leaf
  "Randomizes a fact leaf based on a percentage provided with `rp`."
  [rp leaf]
  (if (< (rand 100) rp)
    (cond
     (string? leaf) (random-string (inc (rand-int 100)))
     (integer? leaf) (rand-int 100000)
     (float? leaf) (* (rand) (rand-int 100000))
     (ks/boolean? leaf) (random-bool))
    leaf))

(defn randomize-map-leaves
  "Runs through a map and randomizes leafs based on a percentage provided with
   `rp`."
  [rp value]
  (cond
   (map? value)
   (ks/mapvals (partial randomize-map-leaves rp) value)

   (coll? value)
   (map (partial randomize-map-leaves rp) value)

   :else
   (randomize-map-leaf rp value)))

(defn update-factset
  "Updates the producer_timestamp to be current, and randomly updates the leaves
   of the factset based on a percentage provided in `rand-percentage`."
  [factset rand-percentage]
  (-> factset
      (assoc "producer_timestamp" (time/now))
      (update "values" (partial randomize-map-leaves rand-percentage))))

(defn timed-update-host
  "Send a new _clock tick_ to a host

  On each tick, a host:

  * Determines if its time to submit a new catalog (by looking at the
    time it last sent one, and comparing that to the desired
    `runinterval`

  * If we need to submit a catalog, optionally tweak the catalog (a
    hosts catalog changes in accordance to the users preference)

  * Submit the resulting catalog"
  [{:keys [host lastrun catalog report factset base-url run-interval rand-percentage] :as state} clock]
  (if-not (> (- clock lastrun) run-interval)
    state
    (let [catalog (some-> catalog update-catalog (maybe-tweak-catalog rand-percentage))
          report (some-> report update-report-run-fields)
          factset (some-> factset (update-factset rand-percentage))]
      ;; Submit the catalog and reports in separate threads, so as to not
      ;; disturb the world-loop and otherwise distort the space-time continuum.
      (when catalog
        (future
          (try
            (client/submit-catalog base-url 6 (json/generate-string catalog))
            (log/infof "[%s] submitted catalog" host)
            (catch Exception e
              (log/errorf "[%s] failed to submit catalog: %s" host e)))))
      (when report
        (future
          (try
            (client/submit-report base-url 5 (json/generate-string report))
            (log/infof "[%s] submitted report" host)
            (catch Exception e
              (log/errorf "[%s] failed to submit report: %s" host e)))))
      (when factset
        (future
          (try
            (client/submit-facts base-url 4 (json/generate-string factset))
            (log/infof "[%s] submitted factset" host)
            (catch Exception e
              (log/errorf "[%s] failed to submit factset: %s" host e)))))
      (assoc state
        :lastrun clock
        :catalog catalog))))

(defn update-host
  "Submit a `catalog` for `hosts` (when present), possibly mutating it before
   submission.  Also submit a report for the host (if present). This is
   similar to timed-update-host, but always sends the update (doesn't run/skip
   based on the clock)"
  [{:keys [host lastrun catalog report factset base-url run-interval rand-percentage] :as state}]
  (let [catalog (some-> catalog update-catalog (maybe-tweak-catalog rand-percentage))
        report (some-> report update-report-run-fields)
        factset (some-> factset (update-factset rand-percentage))]
    (when catalog (client/submit-catalog base-url 6 (json/generate-string catalog)))
    (when report (client/submit-report base-url 5 (json/generate-string report)))
    (when factset (client/submit-facts base-url 4 (json/generate-string factset)))
    (assoc state :catalog catalog)))

(defn submit-n-messages
  "Given a list of host maps, send `num-messages` to each host.  The function
   is recursive to accumulate possible catalog mutations (i.e. changing a previously
   mutated catalog as opposed to different mutations of the same catalog)."
  [hosts num-msgs]
  (log/infof "Sending %s messages for %s hosts, will exit upon completion"
             num-msgs (count hosts))
  (loop [mutated-hosts hosts
         msgs-to-send num-msgs]
    (when-not (zero? msgs-to-send)
      (recur (mapv update-host mutated-hosts) (dec msgs-to-send)))))

(defn world-loop
  "Sends out new _clock tick_ messages to all agents.

  This function never terminates.

  The time resolution of this loop is 10ms."
  [hosts]
  (loop [last-time (System/currentTimeMillis)]
    (let [curr-time (System/currentTimeMillis)]

      ;; Send out updated ticks to each agent
      (doseq [host hosts]
        (send host timed-update-host curr-time)
        (when-let [error (agent-error host)]
          (log/errorf error "[%s] agent failed; restarting" host)
          ;; Restart it with exactly the same state. Hopefully that's okay!
          (restart-agent host @host)))

      (Thread/sleep 10)
      (recur curr-time))))

(def supported-cli-options
  [["-c" "--config CONFIG" "Path to config or conf.d directory (required)"]
   ["-F" "--facts FACTS" "Path to a directory containing sample JSON facts (files must end with .json)"]
   ["-C" "--catalogs CATALOGS" "Path to a directory containing sample JSON catalogs (files must end with .json)"]
   ["-R" "--reports REPORTS" "Path to a directory containing sample JSON reports (files must end with .json)"]
   ["-A" "--archive ARCHIVE" "Path to a PuppetDB export tarball. Incompatible with -C, -F, -R, or -D"]
   ["-i" "--runinterval RUNINTERVAL" "What runinterval (in minutes) to use during simulation"]
   ["-n" "--numhosts NUMHOSTS" "How many hosts to use during simulation"]
   ["-r" "--rand-perc RANDPERC" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"]
   ["-N" "--nummsgs NUMMSGS" "Number of commands and/or reports to send for each host"]])

(def required-cli-options
  [:config :numhosts])

(defn activate-logging!
  [options]
  (-> (:config options)
      (get-in [:global :logging-config])
      logutils/configure-logging!))

(defn validate-options
  [options action-on-error-fn]
  (activate-logging! options)
  (cond
    (and (contains? options :runinterval)
         (contains? options :nummsgs))
    (do
      (log/error "Error: -N/--nummsgs runs immediately and is not compatable with -i/--runinterval")
      (action-on-error-fn))

    (not-any? #{:nummsgs :runinterval} (keys options))
    (do
      (log/error "Error: Either -N/--nummsgs or -i/--runinterval is required.")
      (action-on-error-fn))

    (and (contains? options :archive)
         (some #{:reports :catalogs :facts} (keys options)))
    (do
      (log/error "Error: -A/--archive is incompatible with -F/--facts, -C/--catalogs, -R/--reports")
      (action-on-error-fn))

    :else options))

(defn default-options
  [options]
  (assoc options
         :facts "puppetlabs/puppetdb/benchmark/samples/facts"
         :reports "puppetlabs/puppetdb/benchmark/samples/reports"
         :catalogs "puppetlabs/puppetdb/benchmark/samples/catalogs"
         :from-cp? true))

(defn- validate-cli!
  [args]
  (try+
    (let [options (-> (ks/cli! args supported-cli-options required-cli-options)
                      first
                      ;; We load the config for logging and for our main-
                      (update :config config/load-config)
                      (validate-options #(System/exit 1)))]
      (if (empty? (select-keys options [:facts :reports :catalogs]))
        (default-options options)
        options))

    (catch map? m
      (println (:message m))
      (case (:type m)
        :puppetlabs.kitchensink.core/cli-error (System/exit 1)
        :puppetlabs.kitchensink.core/cli-help (System/exit 0)))))

(defn process-tar-entry
  [archive-path tar-reader]
  (let [catalog-pattern (re-pattern "catalogs.*\\.json$")
        report-pattern (re-pattern "reports.*\\.json$")
        facts-pattern (re-pattern "facts.*\\.json$")]
    (fn [acc entry]
      (let [path (.getName entry)
            parsed-entry (-> tar-reader
                             archive/read-entry-content
                             json/parse-string)]
        (cond
          (re-find catalog-pattern path) (update acc :catalogs conj parsed-entry)
          (re-find report-pattern path) (update acc :reports conj parsed-entry)
          (re-find facts-pattern path) (update acc :facts conj parsed-entry)
          :else acc)))))

(defn load-data-from-options
  [{:keys [catalogs facts reports archive from-cp?]}]
  (if archive
    (let [tar-reader (archive/tarball-reader archive)
          process-entries-fn (process-tar-entry archive tar-reader)]
      (->> (archive/all-entries tar-reader)
           (reduce process-entries-fn {:catalogs [] :reports [] :facts []})
           (remove (comp empty? val))
           (into {})))
    {:catalogs (when catalogs (load-sample-data catalogs from-cp?))
     :facts (when facts (load-sample-data facts from-cp?))
     :reports (when reports (load-sample-data reports from-cp?))}))

(defn -main
  [& args]
  (let [{:keys [config] :as options} (validate-cli! args)
        {:keys [catalogs reports facts]} (load-data-from-options options)
        {pdb-host :host pdb-port :port
         :or {pdb-host "127.0.0.1" pdb-port 8080}} (:jetty config)
        rand-percentage (Integer/parseInt (:rand-perc options "0"))
        base-host-map {:base-url {:protocol "http"
                                  :host pdb-host
                                  :port pdb-port
                                  :prefix "/pdb/cmd"
                                  :version :v1}
                       :rand-percentage rand-percentage}
        ;; Create an agent for each host
        nhosts (Integer/parseInt (:numhosts options))
        hosts (->> (range nhosts)
                   (map #(str "host-" %))
                   (mapv (fn [host]
                           (merge base-host-map
                                  {:host host
                                   :catalog (some-> catalogs
                                                    rand-nth
                                                    (assoc "certname" host)
                                                    (update "resources" (partial map #(update % "tags" conj pdb-host))))
                                   :report (some-> reports rand-nth (assoc "certname" host))
                                   :factset (some-> facts rand-nth (assoc "certname" host))}))))]

    (when-not catalogs (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports (log/info "No reports specified; skipping report submission"))
    (when-not facts (log/info "No facts specified; skipping fact submission"))
    (if-let [num-cmds (:nummsgs options)]
      (submit-n-messages hosts (Long/valueOf num-cmds))
      (let [run-interval (* 60 1000 (Integer/parseInt (:runinterval options)))
            rand-lastrun (fn [run-interval]
                           (- (System/currentTimeMillis) (rand-int run-interval)))]
        (->> hosts
             (mapv #(agent (merge % {:run-interval run-interval
                                     :lastrun (rand-lastrun run-interval)})))
             world-loop)))))
