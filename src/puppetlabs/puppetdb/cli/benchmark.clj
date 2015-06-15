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
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clj-time.core :as time]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.random :refer [random-string random-bool]]
            [puppetlabs.puppetdb.archive :as archive]
            [slingshot.slingshot :refer [try+ throw+]]))

(def cli-description "Development-only benchmarking tool")

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception e
      (log/errorf "Error parsing %s; skipping" file))))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  [dir from-classpath?]
  (let [target-files (if from-classpath?
                       (->> dir io/resource io/file file-seq (remove #(.isDirectory %)))
                       (-> dir (fs/file "*.json") fs/glob))]
    (let [data (->> target-files
                    (map try-load-file)
                    (filterv (complement nil?)))]
      (if (seq data)
        data
        (log/error (format "Supplied directory %s contains no usable data!" dir))))))

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
   {(random-string 10)
    (if (zero? depth)
      (random-fact-value)
      (zipmap (repeatedly children #(random-string 10))
              (repeatedly children #(random-structured-fact (rand-nth (range depth))
                                                            (rand-nth (range children))))))}))

(defn update-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
   percent of the time."
  [catalog rand-percentage stamp]
  (let [catalog' (assoc catalog "producer_timestamp" stamp)]
    (if (< (rand 100) rand-percentage)
      (rand-catalog-mutation catalog')
      catalog')))

(defn jitter
  "jitter a timestamp (rand-int n) seconds in the forward direction"
  [stamp n]
  (time/plus stamp (time/seconds (rand-int n))))

(defn update-report
  "configuration_version, start_time and end_time should always change
   on subsequent report submittions, this changes those fields to avoid
   computing the same hash again (causing constraint errors in the DB)"
  [report stamp]
  (-> report
      (update "resource_events" (partial map #(assoc % "timestamp"
                                                     (jitter stamp 300))))
      (assoc "configuration_version" (kitchensink/uuid)
             "start_time" (time/minus stamp (time/seconds 10))
             "end_time" (time/minus stamp (time/seconds 5))
             "producer_timestamp" stamp)))

(defn randomize-map-leaf
  "Randomizes a fact leaf."
  [leaf]
  (cond
    (string? leaf) (random-string (inc (rand-int 100)))
    (integer? leaf) (rand-int 100000)
    (float? leaf) (* (rand) (rand-int 100000))
    (kitchensink/boolean? leaf) (random-bool)))

(defn randomize-map-leaves
  "Runs through a map and randomizes and random percentage of leaves."
  [rand-perc value]
  (cond
    (map? value)
    (kitchensink/mapvals (partial randomize-map-leaves rand-perc) value)

    (coll? value)
    (map (partial randomize-map-leaves rand-perc) value)

    :else
    (if (< (rand 100) rand-perc)
      (randomize-map-leaf value)
      value)))

(defn update-factset
  "Updates the producer_timestamp to be current, and randomly updates the leaves
   of the factset based on a percentage provided in `rand-percentage`."
  [factset rand-percentage stamp]
  (-> factset
      (assoc "producer_timestamp" stamp)
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
  [{:keys [host lastrun catalog report factset] :as state} base-url run-interval rand-percentage current-time]
  (if-not (time/after?
            current-time
            (time/plus lastrun run-interval))
    state
    (let [catalog (some-> catalog
                          (update-catalog rand-percentage current-time))
          report (some-> report
                         (update-report current-time))
          factset (some-> factset
                          (update-factset rand-percentage current-time))]
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
             :lastrun current-time
             :factset factset
             :catalog catalog))))

(defn update-host
  "Submit a `catalog` for `hosts` (when present), possibly mutating it before
   submission.  Also submit a report for the host (if present). This is
   similar to timed-update-host, but always sends the update (doesn't run/skip
   based on the clock)"
  [{:keys [catalog report factset] :as state} base-url rand-percentage current-time]
  (let [stamp (jitter current-time 1800)
        catalog (some-> catalog
                        (update-catalog rand-percentage stamp))
        report (some-> report
                       (update-report stamp))
        factset (some-> factset
                        (update-factset rand-percentage stamp))]
    (when catalog (client/submit-catalog base-url 6 (json/generate-string catalog)))
    (when report (client/submit-report base-url 5 (json/generate-string report)))
    (when factset (client/submit-facts base-url 4 (json/generate-string factset)))
    (assoc state
           :catalog catalog
           :factset factset)))

(defn submit-n-messages
  "Given a list of host maps, send `num-messages` to each host.  The function
   is recursive to accumulate possible catalog mutations (i.e. changing a previously
   mutated catalog as opposed to different mutations of the same catalog)."
  [hosts num-msgs base-url rand-percentage]
  (log/infof "Sending %s messages for %s hosts, will exit upon completion"
             num-msgs (count hosts))
  (loop [mutated-hosts hosts
         msgs-to-send num-msgs
         stamp (time/minus (time/now) (time/minutes (* 30 num-msgs)))]
    (when-not (zero? msgs-to-send)
      (recur (mapv #(update-host % base-url rand-percentage stamp) mutated-hosts)
             (dec msgs-to-send)
             (time/plus stamp (time/minutes 30))))))

(defn world-loop
  "Sends out new _clock tick_ messages to all agents.

  This function never terminates.

  The time resolution of this loop is 10ms."
  [base-url run-interval rand-percentage hosts]
  (loop [last-time (time/now)]
    (let [curr-time (time/now)]
      ;; Send out updated ticks to each agent
      (doseq [host hosts]
        (send host timed-update-host base-url run-interval rand-percentage curr-time)
        (when-let [error (agent-error host)]
          (log/errorf error "[%s] agent failed; restarting" host)
          ;; Restart it with exactly the same state. Hopefully that's okay!
          (restart-agent host @host)))

      (Thread/sleep 10)
      (recur curr-time))))

(defn validate-options
  [options]
  (cond
    (and (contains? options :runinterval)
         (contains? options :nummsgs))
    (utils/throw+-cli-error! "Error: -N/--nummsgs runs immediately and is not compatable with -i/--runinterval")

    (kitchensink/missing? options :runinterval :nummsgs)
    (utils/throw+-cli-error! "Error: Either -N/--nummsgs or -i/--runinterval is required.")

    (and (contains? options :archive)
         (not (kitchensink/missing? options :reports :catalogs :facts)))
    (utils/throw+-cli-error! "Error: -A/--archive is incompatible with -F/--facts, -C/--catalogs, -R/--reports")

    :else options))

(defn- validate-cli!
  [args]
  (let [specs [["-c" "--config CONFIG" "Path to config or conf.d directory (required)"
                :parse-fn config/load-config]
               ["-F" "--facts FACTS" "Path to a directory containing sample JSON facts (files must end with .json)"]
               ["-C" "--catalogs CATALOGS" "Path to a directory containing sample JSON catalogs (files must end with .json)"]
               ["-R" "--reports REPORTS" "Path to a directory containing sample JSON reports (files must end with .json)"]
               ["-A" "--archive ARCHIVE" "Path to a PuppetDB export tarball. Incompatible with -C, -F, -R, or -D"]
               ["-i" "--runinterval RUNINTERVAL" "What runinterval (in minutes) to use during simulation"
                :parse-fn #(Integer/parseInt %)]
               ["-n" "--numhosts NUMHOSTS" "How many hosts to use during simulation (required)"
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--rand-perc RANDPERC" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"
                :default 0
                :parse-fn #(Integer/parseInt %)]
               ["-N" "--nummsgs NUMMSGS" "Number of commands and/or reports to send for each host"
                :parse-fn #(Long/valueOf %)]]
        required [:config :numhosts]]
    (utils/try+-process-cli!
     (fn []
       (-> args
           (kitchensink/cli! specs required)
           first
           validate-options)))))

(defn process-tar-entry
  [tar-reader]
  (fn [acc entry]
    (let [parsed-entry (-> tar-reader
                           archive/read-entry-content
                           json/parse-string)]
      (condp re-find (.getName entry)
        #"catalogs.*\\.json$" (update acc :catalogs conj parsed-entry)
        #"reports.*\\.json$" (update acc :reports conj parsed-entry)
        #"facts.*\\.json$" (update acc :facts conj parsed-entry)
        acc))))

(def default-data-paths
  {:facts "puppetlabs/puppetdb/benchmark/samples/facts"
   :reports "puppetlabs/puppetdb/benchmark/samples/reports"
   :catalogs "puppetlabs/puppetdb/benchmark/samples/catalogs"})

(defn load-data-from-options
  [{:keys [archive] :as options}]
  (if archive
    (let [tar-reader (archive/tarball-reader archive)]
      (->> tar-reader
           archive/all-entries
           (reduce (process-tar-entry tar-reader) {})))
    (let [data-paths (select-keys options [:reports :catalogs :facts])
          [data-paths from-cp?] (if (empty? data-paths)
                                  [default-data-paths true]
                                  [data-paths false])]
      (kitchensink/mapvals #(some-> % (load-sample-data from-cp?)) data-paths))))

(defn -main
  [& args]
  (let [{:keys [config rand-perc numhosts nummsgs] :as options} (validate-cli! args)
        _ (logutils/configure-logging! (get-in config [:global :logging-config]))
        {:keys [catalogs reports facts]} (load-data-from-options options)
        {pdb-host :host pdb-port :port
         :or {pdb-host "127.0.0.1" pdb-port 8080}} (:jetty config)
        base-url (utils/pdb-cmd-base-url pdb-host pdb-port :v1)
        ;; Create an agent for each host
        get-random-entity (fn [host entities]
                            (some-> entities
                                    rand-nth
                                    (assoc "certname" host)))
        make-host (fn [i]
                    (let [host (str "host-" i)]
                      {:host host
                       :catalog (when-let [catalog (get-random-entity host catalogs)] 
                                  (update catalog "resources" (partial map #(update % "tags" conj pdb-host))))
                       :report (get-random-entity host reports)
                       :factset (get-random-entity host facts)}))
        hosts (map make-host (range numhosts))]

    (when-not catalogs (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports (log/info "No reports specified; skipping report submission"))
    (when-not facts (log/info "No facts specified; skipping fact submission"))
    (if nummsgs
      (submit-n-messages hosts nummsgs base-url rand-perc)
      (let [run-interval (time/minutes (:runinterval options))
            rand-lastrun (fn [run-interval]
                           (jitter (time/minus (time/now) run-interval)
                                   (time/in-seconds run-interval)))]
        (->> hosts
             (mapv #(agent (assoc % :lastrun (rand-lastrun run-interval))))
             (world-loop base-url run-interval rand-perc))))))
