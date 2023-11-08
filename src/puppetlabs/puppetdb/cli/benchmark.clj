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
   tick.

   ### Running parallel Benchmarks

   If are running up against the upper limit at which Benchmark can
   submit simulated requests, you can run multiple instances of benchmark and
   make use of the --offset flag to shift the cert numbers.

   Example (probably run on completely separate hosts):

   ```
   benchmark --offset 0 --numhosts 100000
   benchmark --offset 100000 --numhosts 100000
   benchmark --offset 200000 --numhosts 100000
   ...
   ```
  
   ### Preserving host-map data
  
   By default, each time Benchmark is run, it initializes the host-map catalog,
   factset and report data randomly from the given set of base --catalogs
   --factsets and --reports files. When re-running benchmark, this causes
   excessive load on puppetdb due to the completely changed catalogs/factsets
   that must be processed.
  
   To avoid this, set --simulation-dir to preserve all of the host map data
   between runs as nippy/frozen files. Benchmark will then load and initialize a
   preserved host matching a particular host-# from these files at startup.
   Missing hosts (if --numhosts exceeds preserved, for example) will be
   initialized randomly as by default."
  (:require [puppetlabs.puppetdb.catalog.utils :as catutils]
            [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
            [puppetlabs.puppetdb.lint :refer [ignore-value]]
            [puppetlabs.trapperkeeper.logging :as logutils]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.cheshire :as json]
            [me.raynes.fs :as fs]
            [murphy :refer [try!]]
            [clojure.java.io :as io]
            [clojure.stacktrace :as trace]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.utils :as utils :refer [println-err]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.random :refer [safe-sample-normal random-string random-bool random-sha1]]
            [puppetlabs.puppetdb.time :as time :refer [now]]
            [puppetlabs.puppetdb.archive :as archive]
            [clojure.core.async :refer [go-loop <! >!! chan] :as async]
            [taoensso.nippy :as nippy]
            [puppetlabs.i18n.core :refer [trs]]
            [puppetlabs.puppetdb.nio :refer [get-path]])
  (:import
   (com.puppetlabs.ssl_utils SSLUtils)
   (java.io File)
   (java.net URI)
   (java.net.http HttpClient
                  HttpRequest
                  HttpRequest$Builder
                  HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)
   (java.nio.file NoSuchFileException Path)
   (java.nio.file.attribute FileAttribute)
   (java.nio.file CopyOption
                  FileAlreadyExistsException
                  Files
                  LinkOption
                  OpenOption
                  StandardCopyOption)
   (java.util.concurrent RejectedExecutionException ScheduledThreadPoolExecutor)
   (org.apache.commons.compress.archivers.tar TarArchiveEntry)))

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

(defn- ssl-info->context
  [& {:keys [ssl-cert ssl-key ssl-ca-cert]}]
  (SSLUtils/pemsToSSLContext (io/reader ssl-cert)
                             (io/reader ssl-key)
                             (io/reader ssl-ca-cert)))

(defn- build-http-client [& {:keys [ssl-cert] :as opts}]
  (cond-> (HttpClient/newBuilder)
    ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
    ssl-cert (.sslContext (ssl-info->context opts))
    true .build))

;; Until we require requests to provide the client (perhaps we should)
(def ^:private http-client (memoize build-http-client))

(defn- json-request-generator
  ([uri] (.uri ^java.net.http.HttpRequest$Builder (json-request-generator) uri))
  ([] (-> (HttpRequest/newBuilder)
          ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
          (.header "Content-Type" "application/json; charset=UTF-8")
          (.header "Accept" "application/json"))))

(defn- string-publisher [s] (HttpRequest$BodyPublishers/ofString s))
(defn- string-handler [] (HttpResponse$BodyHandlers/ofString))

(defn- post-body
  [^HttpClient client
   ^HttpRequest$Builder req-generator
   body-publisher
   response-body-handler]
  (let [res (.send client (-> req-generator (.POST body-publisher) .build)
                   response-body-handler)]
    ;; Currently minimal
    {::jdk-response res
     :status (.statusCode res)}))

(defn- post-json-via-jdk [url body opts]
  ;; Unlisted, valid keys: ssl-cert ssl-key ssl-ca-cert
  ;; Intentionally ignores unrecognized keys
  (post-body (http-client (select-keys opts [:ssl-cert :ssl-key :ssl-ca-cert]))
             (json-request-generator (URI. url))
             (string-publisher body)
             (string-handler)))

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception _
      (println-err (trs "Error parsing {0}; skipping" file)))))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  [dir from-classpath?]
  (let [target-files (if from-classpath?
                       (->> dir io/resource io/file file-seq (remove #(.isDirectory ^File %)))
                       (-> dir (fs/file "*.json") fs/glob))
        data (->> target-files
                  (map try-load-file)
                  (filterv (complement nil?)))]
    (if (seq data)
      data
      (println-err
       (trs "Supplied directory {0} contains no usable data!" dir)))))

(def producers (vec (repeatedly 4 #(random-string 10))))

(defn random-producer []
  (rand-nth producers))

(def mutate-fns
  "Functions that randomly change a wire-catalog format"
  (memoize
   (fn [include-edges]
     (concat [catutils/add-random-resource-to-wire-catalog
              catutils/mod-resource-in-wire-catalog]
             (when include-edges
               [catutils/add-random-edge-to-wire-catalog
                catutils/swap-edge-targets-in-wire-catalog])))))

(defn add-catalog-varying-fields
  "This function adds the fields that change when there is a different
  catalog. code_id and catalog_uuid should be different whenever the
  catalog is different"
  [catalog]
  (assoc catalog
         "catalog_uuid" (kitchensink/uuid)
         "code_id" (random-sha1)))

(defn rand-catalog-mutation
  "Grabs one of the mutate-fns randomly and returns it"
  [catalog include-edges]
  (let [mutation-fn (comp add-catalog-varying-fields
                          walk/stringify-keys
                          (rand-nth (mutate-fns include-edges)))]
    (mutation-fn catalog)))

(defn update-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
   percent of the time."
  [catalog include-edges rand-percentage uuid stamp]
  (let [catalog' (assoc catalog
                        "producer_timestamp" stamp
                        "transaction_uuid" uuid
                        "producer" (random-producer))]
    (if (< (rand 100) rand-percentage)
      (rand-catalog-mutation catalog' include-edges)
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
  [{:keys [_host catalog report factset] :as state} include-edges rand-percentage get-timestamp]
  (let [stamp (get-timestamp)
        uuid (kitchensink/uuid)]
    (assoc state
           :catalog (some-> catalog (update-catalog include-edges rand-percentage uuid stamp))
           :factset (some-> factset (update-factset rand-percentage stamp))
           :report (some-> report (update-report uuid stamp)))))

(defn validate-options
  [options]
  (cond
    (and (contains? options :runinterval)
         (contains? options :nummsgs))
    (do
      (println-err "Warning: -N/--nummsgs and -i/--runinterval provided. Running in --nummsgs mode.")
      options)

    (kitchensink/missing? options :runinterval :nummsgs)
    (utils/throw-sink-cli-error
     "Error: Either -N/--nummsgs or -i/--runinterval is required.")

    (and (contains? options :archive)
         (not (kitchensink/missing? options :reports :catalogs :facts)))
    (utils/throw-sink-cli-error
     "Error: -A/--archive is incompatible with -F/--facts, -C/--catalogs, -R/--reports")

    :else options))

(defn- validate-cli!
  [args]
  (let [threads (.availableProcessors (Runtime/getRuntime)) ;; actually hyperthreads
        pre "usage: puppetdb benchmark -n HOST_COUNT ...\n\n"
        specs [["-c" "--config CONFIG" "Path to config or conf.d directory (required)"
                :parse-fn config/load-config]
               [nil "--protocol (http|https)" "Network protocol (default via CONFIG)"
                :validate [#(#{"http" "https"} %) "--protocol not http or https"]]
               ["-F" "--facts FACTS" "Directory of *.json sample factsets"]
               ["-C" "--catalogs CATALOGS" "Directory of *.json sample catalogs"]
               ["-R" "--reports REPORTS" "Directory of *.json sample reports"]
               ["-A" "--archive ARCHIVE" "PuppetDB export tarball (conflicts with -C, -F or -R)"]
               ["-i" "--runinterval RUNINTERVAL"
                "Simulation interval (minutes); uses TMPDIR (or java.io.tmpdir)"
                :parse-fn #(Integer/parseInt %)]
               ["-n" "--numhosts N" "Simulated host count (required)"
                :parse-fn #(Integer/parseInt %)]
               ["-r" "--rand-perc PERCENT" "Chance each command will be altered"
                :default 0
                :parse-fn #(if-not % 0 (Integer/parseInt %))]
               ["-N" "--nummsgs N" "Command sets to send per host (set depends on -F -C -R)"
                :parse-fn #(Long/valueOf ^String %)]
               ["-e" "--end-commands-in PERIOD" "End date for a command set"
                :default-desc "0d"
                :default (time/parse-period "0d")
                :parse-fn #(time/parse-period %)]
               [nil "--senders N" "Command submission thread count (default: cores / 2, min 2)"
                :default (max 2 (long (/ threads 2)))
                :parse-fn #(Integer/parseInt %)]
               ["-t" "--threads N" "Deprecated alias for --senders"
                :parse-fn #(Integer/parseInt %)
                :id :senders]
               [nil "--simulators N" "Command simulators (default: cores / 2, min 2)"
                :default (max 2 (long (/ threads 2)))
                :parse-fn #(Integer/parseInt %)]
               [nil "--simulation-dir DIR" "Directory to store the full set of initial host maps to expediate re-running benchmark."]
               ["-o" "--offset N" "Zero based offset of cert numbers for use when running multiple instance of benchmark."
                :default 0
                :parse-fn #(Integer/parseInt %)]
               [nil "--include-catalog-edges" "Include catalog edges in the data submitted to PuppetDB"]]
        post ["\n"
              "The PERIOD (e.g. '3d') will typically be slightly in the future to account for\n"
              "the time it takes to finish processing a set of historical records (so node-ttl\n"
              "will be further away)\n"]
        required [:config :numhosts]]
    (utils/try-process-cli
     (fn []
       (-> args
           (kitchensink/cli! specs required)
           first
           validate-options))
     {:preamble pre :postamble post})))

(defn process-tar-entry
  [tar-reader]
  (fn [acc ^TarArchiveEntry entry]
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

(def random-cmd-delay safe-sample-normal)

(defn send-facts [url certname version catalog opts]
  (client/submit-facts url certname version catalog (assoc opts :post post-json-via-jdk)))
(defn send-catalog [url certname version catalog opts]
  (client/submit-catalog url certname version catalog (assoc opts :post post-json-via-jdk)))
(defn send-report [url certname version catalog opts]
  (client/submit-report url certname version catalog (assoc opts :post post-json-via-jdk)))

(defn start-command-sender
  "Start a command sending process in the background. Reads host-state maps from
  host-info-ch and sends commands to the puppetdb at base-url. Writes
  ::submitted to rate-monitor-ch for every command sent, or ::error if there was
  a problem. Close host-info-ch to stop the background process."
  [base-url host-info-ch rate-monitor-ch num-threads ssl-opts sched max-command-delay-ms]
  (let [fanout-commands-ch (chan)
        schedule (fn [ch v ^long delay] (utils/schedule sched #(>!! ch v) delay))]
    ;; fanout: given a single host state, emit 3 messages, one for each command.
    ;; This gives better parallelism for message submission.
    (go-loop [host-state (<! host-info-ch)]
      (if-let [{:keys [host catalog report factset]} host-state]
        ;; randomize catalog and report delay, minimum 500ms matches the speed
        ;; at which puppetdb received commands for an empty catalog
        (let [catalog-delay (random-cmd-delay 5000 3000 {:lowerb 500
                                                         :upperb max-command-delay-ms})
              report-delay (+ catalog-delay
                              (random-cmd-delay 5000 3000 {:lowerb 500
                                                           :upperb max-command-delay-ms}))]
          (try
            (when factset (async/>! fanout-commands-ch [:factset host 5 factset]))
            (when catalog (schedule fanout-commands-ch [:catalog host 9 catalog] catalog-delay))
            (when report (schedule fanout-commands-ch [:report host 8 report] report-delay))
            (catch RejectedExecutionException _
              ;; if the scheduler has been shutdown, close channel to allow benchmark to
              ;; terminate cleanly
              (utils/await-scheduler-shutdown sched (* 2 max-command-delay-ms))
              (async/close! fanout-commands-ch)))
          (recur (<! host-info-ch)))
        (do
          (utils/request-scheduler-shutdown sched false)
          (utils/await-scheduler-shutdown sched (* 2 max-command-delay-ms))
          (async/close! fanout-commands-ch))))

    ;; actual sender process
    [(async/pipeline-blocking
      num-threads
      rate-monitor-ch
      (map (fn [[command host version payload]]
             (let [submit-fn (case command
                               :factset send-facts
                               :catalog send-catalog
                               :report send-report)]
               (try
                 (submit-fn base-url host version payload ssl-opts)
                 ::submitted
                 (catch Exception e
                   (do
                     (println-err (trs "Exception while submitting command: {0}" e))
                     (println-err (with-out-str (trace/print-stack-trace e))))
                   ::error)))))
      fanout-commands-ch)
     fanout-commands-ch]))

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
                "Sending {0} messages/s (load equivalent to {1} nodes with a run interval of {2} minutes)"
                messages-per-second
                (int (/ messages-per-second expected-node-message-rate))
                (time/in-minutes run-interval)))
              (recur 0 t))
            (recur (inc events-since-last-report) last-report-time)))))))

(def benchmark-shutdown-timeout 5000)

(def ^"[Ljava.nio.file.OpenOption;" no-open-options (into-array OpenOption []))

(defn write-host-info [info ^Path path]
  (let [host (:host info)
        tmp (Files/createTempFile (.getParent path) host "-tmp"
                                  (into-array FileAttribute []))]
    (try!
      (assert host)
      (ignore-value (Files/write tmp ^"[B" (nippy/freeze info) no-open-options))
      (Files/move tmp path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                   StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (Files/deleteIfExists tmp)))))

(defn populate-hosts
  "Returns a lazy sequence of initial host data file Paths, after
  writing the data to a file in the temp-dir."
  [n offset pdb-host include-edges? catalogs reports facts storage-dir]

  (for [i (range n)]
    (let [random-entity (fn random-entity [host entities]
                          (some-> entities
                                  rand-nth
                                  (assoc "certname" host)))
          random-catalog (fn random-catalog [host pdb-host catalogs]
                           (when-let [cat (if include-edges?
                                            (random-entity host catalogs)
                                            (some-> (random-entity host catalogs)
                                                    (assoc "edges" [])))]
                             (update cat "resources"
                                     (partial map #(update % "tags"
                                                           conj
                                                           pdb-host)))))
          augment-host (fn augment-host [info]
                         ;; Adjust the preserved host-map to match current flags.
                         (let [host (:host info)
                               _ (assert host)
                               new (cond-> info
                                     (and facts (nil? (:factset info)))
                                     (assoc :factset (random-entity host facts))
                                     (and catalogs (nil? (:catalog info)))
                                     (assoc :catalog (random-catalog host pdb-host catalogs))
                                     (and reports (nil? (:report info)))
                                     (assoc :report (random-entity host reports)))]
                           (if (or include-edges? (nil? (:catalog new)))
                             new
                             (assoc-in new [:catalog "edges"] []))))
          host (str "host-" (+ offset i))
          host-path (.resolve ^Path storage-dir host)
          host-info (if-let [data (try
                                 (Files/readAllBytes host-path)
                                 (catch NoSuchFileException _))]
                      (-> data nippy/thaw augment-host)
                      {:host host
                       :catalog (random-catalog host pdb-host catalogs)
                       :report (random-entity host reports)
                       :factset (random-entity host facts)})]
      (write-host-info host-info host-path)
      host-path)))

(defn progressing-timestamp
  "Return a function that will return a timestamp that progresses forward in time."
  [num-hosts num-msgs run-interval-minutes end-commands-in]
  (if num-msgs
    (let [msg-interval (* num-msgs run-interval-minutes)
          ;; Does not need to be multiplied by 3 (for reports/catalogs/factsets) because
          ;; each set of commands for a node use the same timestamp
          timestamp-increment-ms (/ (* 60 1000 msg-interval) (* num-hosts num-msgs))
          timestamp (atom (-> (time/from-now end-commands-in)
                              (time/minus (time/minutes msg-interval))))]
      ;; Return a function that will backdate previous messages
      ;; helpful for submiting reports that will populate old partition
      (fn []
        (swap! timestamp time/plus (time/millis timestamp-increment-ms))))
    ;; When running in the continuous runinterval mode, provide a bit
    ;; of random variation from now. The timestamps are spread out over
    ;; the course of the run by the thread sleeps in the channel read.
    (fn []
      (jitter (now) run-interval-minutes))))

(defn prune-host-info
  "Adjusts the info to match the current run, i.e. if the current run
  didn't specify --catalogs, then prune it.  We might have extra data
  when using a simulation dir from a previous run with different
  arguments."
  [info factsets catalogs reports]
  (cond-> info
    (not factsets) (dissoc :factset)
    (not catalogs) (dissoc :catalog)
    (not reports) (dissoc :report)))

(defn start-simulation-loop
  "Run a background process which takes host-state maps from read-ch, updates
  them with update-host, and puts them on write-ch. If num-msgs is not given,
  uses numhosts and run-interval to run the simulation at a reasonable rate.
  Close read-ch to terminate the background process."
  [numhosts run-interval num-msgs end-commands-in rand-perc simulation-threads
   sim-ch host-info-ch read-ch
   & {:keys [facts catalogs reports include-edges?]}]
  (let [run-interval-minutes (time/in-minutes run-interval)
        hosts-per-second (/ numhosts (* run-interval-minutes 60))
        ms-per-message (/ 1000 hosts-per-second)
        ms-per-thread (* ms-per-message simulation-threads)
        progressing-timestamp-fn (progressing-timestamp numhosts num-msgs run-interval-minutes end-commands-in)]
    (async/pipeline-blocking
     simulation-threads
     host-info-ch
     ;; As currently arranged, the initial host state will never reach
     ;; the senders because we advance it before sending.
     ;; Alternately, we could prune/send host-state, but that'd be
     ;; sending potentially arbitrarily stale data (from existing
     ;; simulation dir), and even ignoring that, it'd be sending
     ;; commands after the sleep that have timestamps that were chosen
     ;; in the last iteration.
     (map (fn advance-host [host-path]
            (let [deadline (+ (time/ephemeral-now-ns) (* ms-per-thread 1000000))
                  host-state (-> host-path Files/readAllBytes nippy/thaw)
                  new-state (update-host host-state include-edges? rand-perc progressing-timestamp-fn)]
              (write-host-info new-state host-path)
              (when (and (not num-msgs) (> deadline (time/ephemeral-now-ns)))
                ;; sleep until deadline
                (Thread/sleep (int (/ (- deadline (time/ephemeral-now-ns)) 1000000))))
              (>!! sim-ch host-path)
              (prune-host-info new-state facts catalogs reports))))
     read-ch)))

(defn warn-missing-data [catalogs reports facts]
  (when-not catalogs
    (println-err (trs "No catalogs specified; skipping catalog submission")))
  (when-not reports
    (println-err (trs "No reports specified; skipping report submission")))
  (when-not facts
    (println-err (trs "No facts specified; skipping fact submission"))))

(defn register-shutdown-hook! [^Runnable f]
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn create-storage-dir
  "Returns a Path to the directory where simulation host-maps are stored.

  If simulation-dir is set, then the path will be the absolute-path to
  simulation-dir. Otherwise a temporary directory will be created in tmpdir.

  The directory is created as a side effect of calling this method if it does
  not already exist. Parent directories are not created."
  [simulation-dir]
  (if (nil? simulation-dir)
    ;; Create a tmp directory and return that path.
    (let [temp-dir (get-path (or (System/getenv "TMPDIR")
                                 (System/getProperty "java.io.tmpdir")))]
      (Files/createTempDirectory temp-dir
                                 "pdb-bench-"
                                 (into-array FileAttribute [])))
    ;; Otherwise, ensure that the given simulation-dir is created
    (let [absolute-path (.toAbsolutePath (get-path simulation-dir))]
      (try
        (Files/createDirectory absolute-path (make-array FileAttribute 0))
        (catch FileAlreadyExistsException _
          (when-not (Files/isDirectory absolute-path (make-array LinkOption 0))
            (throw (Exception. (trs "The --simulation-dir path {0} is not a directory" absolute-path))))))
      ;; and return the Path
      absolute-path)))

;; The core.async processes and channels fit together like
;; this (square brackets indicate data being sent, parens channels):
;;
;; (initial-hosts-ch)
;;    |
;;  [host-map]
;;    |
;;    v
;; (sim-input-ch: mix) <--- [host-path] ----\
;;    |                                     |
;;    v                                     |
;; simulation-loop (sim-next-ch) -----------/
;; (host-info-ch)
;;    |
;;  [host-info]       
;;    |
;;    v
;; command-sender ------------ [delayed catalog & report] --\
;;    |                                                     |
;;    |                                                     v
;;  [factset]                                             scheduler
;;    |                                                     |
;;    | (rate-monitor-ch: ::success or ::error)<------------/
;;    v
;; rate-monitor
;;
;; It's all set up so that channel closes flow downstream (and
;; upstream to the producer). The command-sender is not just a
;; pipeline so to shut down benchmark sim-input-ch and fanout-ch must
;; be closed and the scheduler shutdown.

(defn benchmark
  "Feeds commands to PDB as requested by args. Returns a map of :join, a
  function to wait for the benchmark process to terminate (only happens when you
  pass nummsgs), and :stop, function to request termination of the benchmark
  process and wait for it to stop cleanly. These functions return true if
  shutdown happened cleanly, or false if there was a timeout."
  [options]
  (let [{:keys [config rand-perc numhosts nummsgs
                senders simulators end-commands-in offset
                include-catalog-edges simulation-dir]} options
        _ (logutils/configure-logging! (get-in config [:global :logging-config]))
        {:keys [catalogs reports facts]} (load-data-from-options options)
        _ (warn-missing-data catalogs reports facts)
        {:keys [host port ssl-host ssl-port]} (:jetty config)
        [protocol pdb-host pdb-port]
        (case (:protocol options)
          "http" ["http" (or host "localhost") (or port 8080)]
          "https" ["https" (or ssl-host "localhost") (or ssl-port 8081)]
          (cond
            ssl-port ["https" (or ssl-host "localhost") ssl-port]
            ssl-host ["https" ssl-host (or ssl-port 8081)]
            port ["http" (or host "localhost") port]
            host ["http" host (or port 8080)]
            :else ["http" "localhost" 8080]))
        _ (println-err (format "Connecting to %s://%s:%s" protocol pdb-host pdb-port))
        ssl-opts (select-keys (:jetty config) [:ssl-cert :ssl-key :ssl-ca-cert])
        base-url (utils/pdb-cmd-base-url pdb-host pdb-port :v1 protocol)
        run-interval (get options :runinterval 30)
        run-interval-minutes (-> run-interval time/minutes)

        max-command-delay-ms 15000

        commands-per-puppet-run (+ (if catalogs 1 0)
                                   (if reports 1 0)
                                   (if facts 1 0))
        storage-dir (create-storage-dir simulation-dir)

        ;; channels
        initial-hosts-ch (async/to-chan!!
                          (populate-hosts numhosts offset pdb-host include-catalog-edges
                                          catalogs reports facts storage-dir))
        sim-next-ch (chan numhosts)
        _ (register-shutdown-hook! #(async/close! sim-next-ch))

        host-info-ch (chan)
        rate-monitor-ch (chan)

        sim-input-ch (let [ch (chan)
                           mixer (async/mix ch)]
                       (async/solo-mode mixer :pause)
                       (async/toggle mixer {initial-hosts-ch {:solo true}})
                       (async/admix mixer sim-next-ch)
                       (if nummsgs (async/take (* numhosts nummsgs) ch) ch))

        ;; processes
        _rate-monitor-finished-ch (start-rate-monitor rate-monitor-ch
                                                      (-> 30 time/minutes)
                                                      commands-per-puppet-run)
        ^ScheduledThreadPoolExecutor
        command-delay-scheduler (utils/scheduler 1)
        ;; ensures we submit all commands after they are scheduled
        ;; before we tear down the output channel
        _ (.setExecuteExistingDelayedTasksAfterShutdownPolicy command-delay-scheduler true)

        [command-sender-finished-ch fanout-ch] (start-command-sender base-url
                                                                     host-info-ch
                                                                     rate-monitor-ch
                                                                     senders
                                                                     ssl-opts
                                                                     command-delay-scheduler
                                                                     max-command-delay-ms)
        _ (start-simulation-loop numhosts run-interval-minutes nummsgs end-commands-in rand-perc
                                 simulators
                                 sim-next-ch
                                 host-info-ch
                                 sim-input-ch
                                 {:facts facts
                                  :catalogs catalogs
                                  :reports reports
                                  :include-catalog-edges? include-catalog-edges})
        join-fn (fn join-benchmark
                  ([] (join-benchmark nil))
                  ([timeout-ms]
                   (let [t-ch (if timeout-ms (async/timeout timeout-ms) (chan))]
                     (async/alt!! t-ch false command-sender-finished-ch true)
                     (async/alt!! t-ch false rate-monitor-ch true))))
        stop-fn (fn stop-benchmark []
                  (async/close! sim-input-ch)
                  (utils/request-scheduler-shutdown command-delay-scheduler true)
                  (async/close! fanout-ch)
                  (when-not (join-fn benchmark-shutdown-timeout)
                    (println-err (trs "Timed out while waiting for benchmark to stop."))
                    false))
        _ (register-shutdown-hook! stop-fn)]

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

(set! *warn-on-reflection* warn-on-reflection-orig)
