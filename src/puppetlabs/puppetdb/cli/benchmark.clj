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
  (:import (java.io File))
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
  (if (pred x) (log/error msg) x))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  ([dir]
   (load-sample-data dir false))
  ([dir from-classpath?]
   (let [target-files (if from-classpath?
                        (remove #(.isDirectory %) (file-seq (io/file (io/resource dir))))
                        (fs/glob (fs/file dir "*.json")))]
     (->> target-files
          (map try-load-file)
          (remove nil?)
          (vec)
          (error-if empty? (format "Supplied directory %s contains no usable data!" dir))))))

(def mutate-fns
  "Functions that randomly change a wire-catalog format"
  [catutils/add-random-resource-to-wire-catalog
   catutils/mod-resource-in-wire-catalog
   catutils/add-random-edge-to-wire-catalog
   catutils/swap-edge-targets-in-wire-catalog])

(defn rand-catalog-mutate-fn
  "Grabs one of the mutate-fns randomly and returns it"
  []
  (rand-nth mutate-fns))

(defn random-fact-value
  "Given a type, generate a random fact value"
  [kind]
  (case kind
    :int (rand-int 300)
    :float (rand)
    :bool (random-bool)
    :string (random-string 4)
    :vector (into [] (take (rand-int 10)
                           (repeatedly #(random-fact-value
                                         (rand-nth [:string :int :float :bool])))))))

(defn random-structured-fact
  "Create a 'random' structured fact.
  Parameters are fact depth and number of child facts.  Depth 0 implies one child."
  ([]
     (random-structured-fact (rand-nth [0 1 2 3]) (rand-nth [1 2 3 4])))
  ([depth children]
     (let [kind (rand-nth [:int :float :bool :string :vector])]
       (if (zero? depth)
         {(random-string 10) (random-fact-value kind)}
         {(random-string 10) (zipmap (take children (repeatedly #(random-string 10)))
                                     (take children (repeatedly
                                                     #(random-structured-fact
                                                       (rand-nth (range depth))
                                                       (rand-nth (range children))))))}))))

(defn maybe-tweak-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
  percent of the time."
  [catalog rand-percentage]
  (if (< (rand 100) rand-percentage)
    ((rand-catalog-mutate-fn) catalog)
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
     (string? leaf)  (random-string (inc (rand-int 100)))
     (integer? leaf) (rand-int 100000)
     (float? leaf)   (* (rand) (rand-int 100000))
     (ks/boolean? leaf) (random-bool))
    leaf))

(defn randomize-map-leaves
  "Runs through a map and randomizes leafs based on a percentage provided with
   `rp`."
  [rp value]
  (cond
   (map? value)
   (into {}
         (for [[k v] value]
           [k (randomize-map-leaves rp v)]))

   (coll? value)
   (for [v value]
     (randomize-map-leaves rp v))

   :else
   (randomize-map-leaf rp value)))

(defn update-factset
  "Updates the producer_timestamp to be current, and randomly updates the leaves
   of the factset based on a percentage provided in `rand-percentage`."
  [factset rand-percentage]
  (-> factset
      (assoc "producer_timestamp" (time/now))
      (update-in ["values"] (partial randomize-map-leaves rand-percentage))))

(defn timed-update-host
  "Send a new _clock tick_ to a host

  On each tick, a host:

  * Determines if its time to submit a new catalog (by looking at the
    time it last sent one, and comparing that to the desired
    `runinterval`

  * If we need to submit a catalog, optionally tweak the catalog (a
    hosts catalog changes in accordance to the users preference)

  * Submit the resulting catalog"
  [{:keys [host lastrun catalog report factset puppetdb-host puppetdb-port run-interval rand-percentage] :as state} clock]
  (if-not (> (- clock lastrun) run-interval)
    state
    (let [base-url {:protocol "http" :host puppetdb-host :port puppetdb-port :prefix "/pdb/query"}
          catalog (some-> catalog update-catalog (maybe-tweak-catalog rand-percentage))
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
  [{:keys [host lastrun catalog report factset puppetdb-host puppetdb-port run-interval rand-percentage] :as state}]
  (let [base-url {:protocol "http" :host puppetdb-host :port puppetdb-port :prefix "/pdb/query"}
        catalog (some-> catalog (maybe-tweak-catalog rand-percentage))
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

(defn associate-catalog-with-host
  "Takes the given `catalog` and transforms it to appear related to
  `hostname`"
  [hostname catalog]
  (assoc catalog
    "certname" hostname
    "resources" (map #(update-in % ["tags"] conj hostname) (get catalog "resources"))))

(defn associate-report-with-host
  "Takes the given `report` and transforms it to appear related to
  `hostname`"
  [hostname report]
  (assoc report "certname" hostname))

(defn associate-factset-with-host
  "Takes the given `factset` and transforms it to appear related to
   `hostname`"
  [hostname factset]
  (assoc factset "certname" hostname))

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
      config/load-config
      (get-in [:global :logging-config])
      logutils/configure-logging!))

(defn validate-options
  [options action-on-error-fn]
  (activate-logging! options)
  (cond
    (and (contains? options :runinterval)
         (contains? options :nummsgs))
    (do
      (log/error
        "Error: -N/--nummsgs runs immediately and is not compatable with -i/--runinterval")
      (action-on-error-fn))

    (not-any? #{:nummsgs :runinterval} (keys options))
    (do
      (log/error "Error: Either -N/--nummsgs or -i/--runinterval is required.")
      (action-on-error-fn))

    (and (contains? options :archive)
         (some #{:reports :catalogs :facts} (keys options)))
    (do
      (log/error
        "Error: -A/--archive is incompatible with -F/--facts, -C/--catalogs, -R/--reports")
      (action-on-error-fn))

    :else options))

(defn default-options
  [options]
  (-> options
      (assoc :facts "puppetlabs/puppetdb/benchmark/samples/facts")
      (assoc :reports "puppetlabs/puppetdb/benchmark/samples/reports")
      (assoc :catalogs "puppetlabs/puppetdb/benchmark/samples/catalogs")
      (assoc :from-cp? true)))

(defn- validate-cli!
  [args]
  (try+
    (let [options (-> (ks/cli! args supported-cli-options required-cli-options)
                      first
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
            parsed-entry (json/parse-string (archive/read-entry-content tar-reader))]
        (cond
          (re-find catalog-pattern path) (update-in acc [:catalogs] conj parsed-entry)
          (re-find report-pattern path) (update-in acc [:reports] conj parsed-entry)
          (re-find facts-pattern path) (update-in acc [:facts] conj parsed-entry)
          :else acc)))))

(defn load-data-from-options
  [{:keys [catalogs facts reports archive from-cp?]}]
  (if archive
    (let [tar-reader (archive/tarball-reader archive)
          entries (archive/all-entries tar-reader)]
      (->> (reduce (process-tar-entry archive tar-reader)
                   {:catalogs [] :reports [] :facts []} entries)
           (filter (comp not empty? val))
           (into {})))
    {:catalogs (when catalogs
                 (load-sample-data catalogs from-cp?))
     :facts (when facts
              (load-sample-data facts from-cp?))
     :reports (when reports
                (load-sample-data reports from-cp?))}))

(defn -main
  [& args]
  (let [options         (validate-cli! args)
        config          (-> (:config options)
                            config/load-config)

        {:keys [catalogs reports facts]} (load-data-from-options options)
        nhosts          (:numhosts options)
        hostnames       (set (map #(str "host-" %) (range (Integer/parseInt nhosts))))
        hostname        (get-in config [:jetty :host] "localhost")
        port            (get-in config [:jetty :port] 8080)
        rand-percentage (Integer/parseInt (or (:rand-perc options) "0"))

        ;; Create an agent for each host
        hosts (mapv (fn [host]
                      {:host host
                       :puppetdb-host hostname
                       :puppetdb-port port
                       :rand-percentage rand-percentage
                       :catalog (when catalogs
                                  (associate-catalog-with-host host (rand-nth catalogs)))
                       :report (when reports
                                 (associate-report-with-host host (rand-nth reports)))
                       :factset (when facts
                                   (associate-factset-with-host host (rand-nth facts)))})
                    hostnames)]

    (when-not catalogs
      (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports
      (log/info "No reports specified; skipping report submission"))
    (when-not facts
      (log/info "No facts specified; skipping fact submission"))
    (if-let [num-cmds (:nummsgs options)]
      (submit-n-messages hosts (Long/valueOf num-cmds))
      (world-loop (mapv (fn [host-map]
                          (let [run-interval (* 60 1000 (Integer/parseInt (:runinterval options)))]
                            (agent (assoc host-map
                                     :run-interval run-interval
                                     :lastrun (- (System/currentTimeMillis) (rand-int run-interval))))))
                        hosts)))))
