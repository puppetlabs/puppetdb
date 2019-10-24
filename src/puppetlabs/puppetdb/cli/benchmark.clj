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
            [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
            [puppetlabs.trapperkeeper.logging :as logutils]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.cheshire :as json]
            [me.raynes.fs :as fs]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.utils :as utils :refer [println-err]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.random :refer [random-string random-bool]]
            [puppetlabs.puppetdb.time :as time :refer [now]]
            [puppetlabs.puppetdb.archive :as archive]
            [slingshot.slingshot :refer [try+ throw+]]
            [clojure.core.async :refer [go go-loop >! <! >!! <!! chan] :as async]
            [clojure.core.match :as cm]
            [clojure.tools.reader.edn :as edn]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [clj-time.coerce :as coerce])
  (:import
   [clojure.core.async.impl.protocols Buffer]
   [java.io ByteArrayInputStream]
   [java.nio.file.attribute FileAttribute]
   [java.nio.file Files OpenOption]
   [java.util ArrayDeque]
   (java.nio.charset Charset)))

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception e
      (println-err (trs "Error parsing {0}; skipping" file)))))

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
        (println-err
         (trs "Supplied directory {0} contains no usable data!" dir))))))

(def producers (vec (repeatedly 4 #(random-string 10))))

(defn random-producer []
  (rand-nth producers))

(def mutate-fns
  "Functions that randomly change a wire-catalog format"
  [catutils/add-random-resource-to-wire-catalog
   catutils/mod-resource-in-wire-catalog
   catutils/add-random-edge-to-wire-catalog
   catutils/swap-edge-targets-in-wire-catalog])

(defn add-catalog-varying-fields
  "This function adds the fields that change when there is a different
  catalog. code_id and catalog_uuid should be different whenever the
  catalog is different"
  [catalog]
  (assoc catalog
         "catalog_uuid" (kitchensink/uuid)
         "code_id" (kitchensink/utf8-string->sha1 (random-string 100))))

(defn rand-catalog-mutation
  "Grabs one of the mutate-fns randomly and returns it"
  [catalog]
  (let [mutation-fn (comp add-catalog-varying-fields
                          (rand-nth mutate-fns))]
    (mutation-fn catalog)))

(defn update-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
   percent of the time."
  [catalog rand-percentage uuid stamp]
  (let [catalog' (assoc catalog
                        "producer_timestamp" stamp
                        "transaction_uuid" uuid
                        "producer" (random-producer))]
    (if (< (rand 100) rand-percentage)
      (rand-catalog-mutation catalog')
      catalog')))

(defn jitter
  "jitter a timestamp (rand-int n) seconds in the forward direction"
  [stamp n]
  (time/plus stamp (time/seconds (rand-int n))))

(defn update-report-resources [resources stamp]
  (let [timestamp (jitter stamp 300)
        update-timestamps-fn (fn [resources-or-events]
                               (map #(assoc % "timestamp" timestamp)
                                    resources-or-events))]
    (->> resources
         update-timestamps-fn
         (map #(update % "events" update-timestamps-fn)))))

(defn update-report
  "configuration_version, start_time and end_time should always change
   on subsequent report submittions, this changes those fields to avoid
   computing the same hash again (causing constraint errors in the DB)"
  [report uuid stamp]
  (-> report
      (update "resources" update-report-resources stamp)
      (assoc "configuration_version" (kitchensink/uuid)
             "transaction_uuid" uuid
             "start_time" (time/minus stamp (time/seconds 10))
             "end_time" (time/minus stamp (time/seconds 5))
             "producer_timestamp" stamp
             "producer" (random-producer))))

(defn randomize-map-leaf
  "Randomizes a fact leaf."
  [leaf]
  (cond
    (string? leaf) (random-string (inc (rand-int 100)))
    (integer? leaf) (rand-int 100000)
    (float? leaf) (* (rand) (rand-int 100000))
    (boolean? leaf) (random-bool)))

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
      (assoc "producer_timestamp" stamp
             "producer" (random-producer))
      (update "values" (partial randomize-map-leaves rand-percentage))))

(defn update-host
  "Perform a simulation step on host-map. Always update timestamps and uuids;
  randomly mutate other data depending on rand-percentage. "
  [{:keys [host catalog report factset] :as state} rand-percentage current-time run-interval]
  (let [stamp (jitter current-time (time/in-seconds run-interval))
        uuid (kitchensink/uuid)]
    (assoc state
           :catalog (some-> catalog (update-catalog rand-percentage uuid stamp))
           :factset (some-> factset (update-factset rand-percentage stamp))
           :report (some-> report (update-report uuid stamp)))))

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
               ["-A" "--archive ARCHIVE" "Path to a PuppetDB export tarball. Incompatible with -C, -F or -R"]
               ["-i" (str "--runinterval RUNINTERVAL" "interval (in minutes)"
                          " to use during simulation. This option"
                          " requires some temporary filesystem space, which"
                          " will be allocated in TMPDIR (if set in the"
                          " environment) or java.io.tmpdir.")
                :parse-fn #(Integer/parseInt %)]
               ["-n" "--numhosts NUMHOSTS" "How many hosts to use during simulation (required)"
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--rand-perc RANDPERC" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"
                :default 0
                :parse-fn #(Integer/parseInt %)]
               ["-N" "--nummsgs NUMMSGS" "Number of commands and/or reports to send for each host"
                :parse-fn #(Long/valueOf %)]
               ["-t" "--threads THREADS" "Number of threads to use for command submission"
                :default (* 4 (.availableProcessors (Runtime/getRuntime)))
                :parse-fn #(Integer/parseInt %)]]
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
        #"catalogs.*\.json$" (update acc :catalogs conj parsed-entry)
        #"reports.*\.json$" (update acc :reports conj parsed-entry)
        #"facts.*\.json$" (update acc :facts conj parsed-entry)
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

(defn start-command-sender
  "Start a command sending process in the background. Reads host-state maps from
  command-send-ch and sends commands to the puppetdb at base-url. Writes
  ::submitted to rate-monitor-ch for every command sent, or ::error if there was
  a problem. Close command-send-ch to stop the background process."
  [base-url command-send-ch rate-monitor-ch num-threads]
  (let [fanout-commands-ch (chan)]
    ;; fanout: given a single host state, emit 3 messages, one for each command.
    ;; This gives better parallelism for message submission.
    (async/pipeline
     1
     fanout-commands-ch
     (mapcat (fn [host-state]
               (let [{:keys [host catalog report factset]} host-state]
                 (remove nil?
                         [(when catalog [:catalog host 9 catalog])
                          (when report [:report host 8 report])
                          (when factset [:factset host 5 factset])]))))
     command-send-ch)

    ;; actual sender process
    (async/pipeline-blocking
     num-threads
     rate-monitor-ch
     (map (fn [[command host version payload]]
            (let [submit-fn (case command
                              :catalog client/submit-catalog
                              :report client/submit-report
                              :factset client/submit-facts)]
              (try
                (submit-fn base-url host version payload)
                ::submitted
                (catch Exception e
                  (println-err (trs "Exception while submitting command: {0}" e))
                  ::error)))))
     fanout-commands-ch)))

(defn start-rate-monitor
  "Start a task which monitors the rate of messages on rate-monitor-ch and
  prints it to the console every 5 seconds. Uses run-interval to compute the
  number of nodes that would produce that load."
  [rate-monitor-ch run-interval commands-per-puppet-run]
  (let [run-interval-seconds (time/in-seconds run-interval)
        expected-node-message-rate (/ commands-per-puppet-run run-interval-seconds)]
    (go-loop [events-since-last-report 0
              last-report-time (System/currentTimeMillis)]
      (when (<! rate-monitor-ch)
        (let [t (System/currentTimeMillis)
              time-diff (- t last-report-time)]
          (if (> time-diff 5000)
            (let [time-diff-seconds (/ time-diff 1000)
                  messages-per-second (float (/ events-since-last-report time-diff-seconds))]
              (println-err
               (trs
                "Sending {0} messages/s (load equivalent to {1} nodes)"
                messages-per-second
                (int (/ messages-per-second expected-node-message-rate))))
              (recur 0 t))
            (recur (inc events-since-last-report) last-report-time)))))))

(defn rand-lastrun [run-interval]
  (jitter (time/minus (now) run-interval)
          (time/in-seconds run-interval)))

(defn delete-dir-or-report [dir]
  (try
    (fs/delete-dir dir)
    (catch Exception ex
      (println-err ex))))

(def benchmark-shutdown-timeout 5000)

(deftype TempFileBuffer [storage-dir q]
  Buffer
  (full? [this] false)
  (remove! [this]
    (let [path (.poll q)
          result (edn/read {:readers coerce/data-readers}
                  (java.io.PushbackReader. (Files/newBufferedReader path (Charset/forName "UTF-8"))))]
      (Files/delete path)
      result))

  (add!* [this item]
    (let [path (Files/createTempFile storage-dir "bench-tmp-" ""
                                     (into-array FileAttribute []))]
      (Files/write path (.getBytes (pr-str item) "UTF-8") (into-array OpenOption []))
      (.add q path)))

  (close-buf! [this]
    (.clear q)
    (println-err (trs "Cleaning up temp files from {0}"
                      (pr-str (str storage-dir))))
    (async/alt!!
      (go (delete-dir-or-report storage-dir))
      (println-err (trs "Finished cleaning up temp files"))

      (async/timeout benchmark-shutdown-timeout)
      (println-err (trs "Cleanup timeout expired; leaving files in {0}"
                        (pr-str (str storage-dir)))))
    nil)

  clojure.lang.Counted
  (count [this] (.size q)))

(defn message-buffer
  [temp-dir expected-size]
  (let [q (ArrayDeque. expected-size)
        storage-dir (Files/createTempDirectory temp-dir
                                               "pdb-bench-"
                                               (into-array FileAttribute []))]
    (TempFileBuffer. storage-dir q)))

(defn random-hosts
  [n pdb-host catalogs reports facts]
  (let [random-entity (fn [host entities]
                        (some-> entities
                                rand-nth
                                (assoc "certname" host)))]
    (for [i (range n)]
      (let [host (str "host-" i)]
        {:host host
         :catalog (when-let [cat (random-entity host catalogs)]
                    (update cat "resources"
                            (partial map #(update % "tags"
                                                  conj
                                                  pdb-host))))
         :report (random-entity host reports)
         :factset (random-entity host facts)}))))

(defn start-populate-queue
  "Fills queue with host entries in the background. Stops if mq-ch is closed."
  [mq-ch numhosts run-interval pdb-host catalogs reports facts]
  (go
    (println-err (trs "Populating queue in the background"))
    (doseq [host (random-hosts numhosts pdb-host catalogs reports facts)
            :while (>! mq-ch (assoc host :lastrun (rand-lastrun run-interval)))]
      true)
    (println-err (trs "Finished populating queue"))))

(defn start-simulation-loop
  "Run a background process which takes host-state maps from mq-ch, updates them
  with update-host, and puts them on on command-send-ch *and* back on mq-ch. If
  num-msgs is given, closes mq-ch after than many simulation steps have been
  performed. If not, uses numhosts and run-interval to run the simulatation at a
  reasonable rate. Close mq-ch to terminate the background process. "
  [numhosts run-interval num-msgs rand-perc simulation-threads
   command-send-ch mq-ch]
  (let [run-interval-minutes (time/in-minutes run-interval)
        hosts-per-second (/ numhosts (* run-interval-minutes 60))
        ms-per-message (- (/ 1000 hosts-per-second) 3)]

    (async/pipeline-blocking
     simulation-threads
     command-send-ch
     (map (fn [host-state]
            (when-not num-msgs
              (Thread/sleep (int (+ (rand) ms-per-message))))
            (let [updated-host-state (update-host host-state rand-perc (now) run-interval)]
              (>!! mq-ch updated-host-state)
              updated-host-state)))
     mq-ch)))

(defn warn-missing-data [catalogs reports facts]
  (when-not catalogs
    (println-err (trs "No catalogs specified; skipping catalog submission")))
  (when-not reports
    (println-err (trs "No reports specified; skipping report submission")))
  (when-not facts
    (println-err (trs "No facts specified; skipping fact submission"))))

(defn register-shutdown-hook! [f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

;; The core.async processes and channels fit together like this:
;;
;; populate-queue
;;    |
;;    | (mq-ch: host-maps) <---\
;;    v                        |
;; simulation-loop ------------/
;;    |
;;    | (command-send-ch: host-maps)
;;    v
;; command-sender
;;    |
;;    | (rate-monitor-ch: ::success or ::error)
;;    v
;; rate-monitor
;;
;; It's all set up so that channel closes flow downstream (and upstream to the
;; producer). Closing mq-ch shuts down everything.

(defn benchmark
  "Feeds commands to PDB as requested by args. Returns a map of :join, a
  function to wait for the benchmark process to terminate (only happens when you
  pass nummsgs), and :stop, function to request termination of the benchmark
  process and and wait for it to stop cleanly. These functions return true if
  shutdown happened cleanly, or false if there was a timeout."
  [options]
  (let [{:keys [config rand-perc numhosts nummsgs threads] :as options} options
        _ (logutils/configure-logging! (get-in config [:global :logging-config]))
        {:keys [catalogs reports facts]} (load-data-from-options options)
        _ (warn-missing-data catalogs reports facts)
        {pdb-host :host pdb-port :port
         :or {pdb-host "127.0.0.1" pdb-port 8080}} (:jetty config)
        base-url (utils/pdb-cmd-base-url pdb-host pdb-port :v1)
        run-interval (-> (get options :runinterval 30) time/minutes)
        simulation-threads 4
        commands-per-puppet-run (+ (if catalogs 1 0)
                                   (if reports 1 0)
                                   (if facts 1 0))
        temp-dir (get-path (or (System/getenv "TMPDIR")
                               (System/getProperty "java.io.tmpdir")))

        ;; channels
        mq-ch (chan (message-buffer temp-dir numhosts))
        _ (register-shutdown-hook! #(async/close! mq-ch))

        command-send-ch (chan)
        rate-monitor-ch (chan)

        ;; processes
        rate-monitor-finished-ch (start-rate-monitor rate-monitor-ch
                                                     run-interval
                                                     commands-per-puppet-run)
        command-sender-finished-ch (start-command-sender base-url
                                                         (if nummsgs
                                                           (async/take (* numhosts nummsgs)
                                                                       command-send-ch)
                                                           command-send-ch)
                                                         rate-monitor-ch
                                                         threads)
        populate-finished-ch (start-populate-queue mq-ch numhosts run-interval pdb-host
                                                   catalogs reports facts)
        _ (<!! populate-finished-ch)
        _ (start-simulation-loop numhosts run-interval nummsgs rand-perc
                                 simulation-threads command-send-ch mq-ch)
        join-fn (fn join-benchmark
                  ([] (join-benchmark nil))
                  ([timeout-ms]
                   (let [t-ch (if timeout-ms (async/timeout timeout-ms) (chan))]
                     (async/alt!! t-ch false populate-finished-ch true)
                     (async/alt!! t-ch false command-sender-finished-ch true)
                     (async/alt!! t-ch false rate-monitor-ch true))))
        stop-fn (fn stop-benchmark []
                  (async/close! mq-ch)
                  (when-not (join-fn benchmark-shutdown-timeout)
                    (println-err (trs "Timed out while waiting for benchmark to stop."))
                    false))
        _ (register-shutdown-hook! stop-fn)]

    ;; When running with nummsgs, benchmark shuts down when the channel from
    ;; async/take closes. This doesn't close the upstream channels
    ;; though (pipeline only propagates channel closes downstream). This
    ;; process watches the command sender and makes sure that mq-ch gets
    ;; closed when it terminates, ensuring timely queue cleanup.
    ;; NB: This relies on the undocumented behavior of pipeline-* returning a
    ;; channel which closes when the pipeline shuts down.
    (go
      (async/<! command-sender-finished-ch)
      (async/close! mq-ch))

    {:stop stop-fn
     :join join-fn}))

(defn benchmark-wrapper [args]
  (->  args
       validate-cli!
       benchmark))

(defn cli
  "Runs the benchmark command as directed by the command line args and
  returns an appropriate exit status."
  [args]
  (run-cli-cmd #(do
                  (when-let [{:keys [join]} (benchmark-wrapper args)]
                    (println-err (trs "Press ctrl-c to stop"))
                    (join))
                  0)))

(defn -main [& args]
  (exit (cli args)))
