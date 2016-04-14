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
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.random :refer [random-string random-bool]]
            [puppetlabs.puppetdb.archive :as archive]
            [slingshot.slingshot :refer [try+ throw+]]
            [clojure.core.async :refer [go go-loop >! <! >!! <!! timeout chan close! dropping-buffer pipeline]]
            [clojure.core.match :as cm]
            [puppetlabs.puppetdb.mq :as mq]
            [taoensso.nippy :as nippy])
  (:import [org.apache.activemq.broker BrokerService]
           [javax.jms MessageConsumer MessageProducer Session]
           [clojure.core.async.impl.protocols Buffer]))

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
  [catalog rand-percentage uuid stamp]
  (let [catalog' (assoc catalog
                        "producer_timestamp" stamp
                        "transaction_uuid" uuid)]
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
             "producer_timestamp" stamp)
      clojure.walk/keywordize-keys))

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

(defn update-host
  "Submit a `catalog` for `hosts` (when present), possibly mutating it before
  submission. Also submit a report for the host (if present)."
  [{:keys [host catalog report factset] :as state} rand-percentage current-time command-send-ch]
  (let [stamp (jitter current-time 1800)
        uuid (kitchensink/uuid)
        catalog (some-> catalog
                        (update-catalog rand-percentage uuid stamp))
        report (some-> report
                       (update-report uuid stamp))
        factset (some-> factset
                        (update-factset rand-percentage stamp))]
    (when catalog (>!! command-send-ch [:catalog host 6 catalog]))
    (when report (>!! command-send-ch [:report host 6 report]))
    (when factset (>!! command-send-ch [:factset host 4 factset]))

    (assoc state
           :catalog catalog
           :factset factset)))

(defn submit-n-messages
  "Given a list of host maps, send `num-messages` to each host.  The function
   is recursive to accumulate possible catalog mutations (i.e. changing a previously
   mutated catalog as opposed to different mutations of the same catalog)."
  [hosts num-msgs rand-percentage command-send-ch]
  (log/infof "Sending %s messages for %s hosts, will exit upon completion"
             num-msgs (count hosts))
  (loop [mutated-hosts hosts
         msgs-to-send num-msgs
         stamp (time/minus (time/now) (time/minutes (* 30 num-msgs)))]
    (when-not (zero? msgs-to-send)
      (recur (mapv #(update-host % rand-percentage stamp command-send-ch) mutated-hosts)
             (dec msgs-to-send)
             (time/plus stamp (time/minutes 30))))))

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
               ["-i" "--runinterval RUNINTERVAL" "What runinterval (in minutes) to use during simulation"
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

(defn try-read-and-send-command [base-url command-send-ch]
  (try
    (cm/match [(<!! command-send-ch)]
              [:stop] nil
              [nil] nil
              [[entity host version payload]]
              (do ((case entity
                     :catalog client/submit-catalog
                     :report client/submit-report
                     :factset client/submit-facts)
                   base-url host version payload)
                  ::submitted))
    (catch Exception e
      (println "Exception while submitting command: " e)
      ::error)))

(defn start-command-sender
  "Start a command sending thread. Reads commands from command-send-ch of the
  form [entity version payload-string]. Writes a value to rate-monitor-ch for
  every command sent."
  [base-url command-send-ch rate-monitor-ch]
  (future
    (loop []
      (case (try-read-and-send-command base-url command-send-ch)
        ::submitted (do (>!! rate-monitor-ch true)
                        (recur))
        ::error (recur)
        nil nil))))

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
              (println "Sending" messages-per-second "messages/s"
                       "(load equivalent to" (int (/ messages-per-second expected-node-message-rate)) "nodes)")
              (flush)
              (recur 0 t))
            (recur (inc events-since-last-report) last-report-time)))))))

(defn rand-lastrun [run-interval]
  (jitter (time/minus (time/now) run-interval)
          (time/in-seconds run-interval)))

(defn partition-into-buckets [num-buckets s]
  (loop [s s
         buckets (into clojure.lang.PersistentQueue/EMPTY
                       (repeat num-buckets '()))]
    (if (seq s)
      (recur (rest s)
             (conj (pop buckets)
                   (cons (first s) (first buckets))))
      (seq buckets))))

(defn make-jms-nippy-message
  "Create a jms message containing the clojure data structure 'payload' encoded
  into a byte array using nippy."
  [session payload]
  (let [msg (.createBytesMessage session)
        payload-bytes (nippy/freeze payload)]
    (.writeBytes msg payload-bytes)
    msg))

(defn unpack-jms-nippy-message
  "Return the clojure data payload from a message created with
  'make-jms-nippy-message'."
  [msg]
  (let [bytes (byte-array (.getBodyLength msg))]
    (.readBytes msg bytes)
    (nippy/thaw bytes)))

(deftype AMQBrokerBuffer [^BrokerService broker
                          ^Session session
                          ^MessageProducer producer
                          ^MessageConsumer consumer
                          mq-dir
                          queue-count]
  Buffer
  (full? [this] false)
  (remove! [this]
    (swap! queue-count dec)
    (mq/commit-or-rollback session (unpack-jms-nippy-message (.receive consumer))))
  (add!* [this item]
    (swap! queue-count inc)
    (mq/commit-or-rollback session (.send producer (make-jms-nippy-message session item))))
  (close-buf! [this]
    (println "Shutting down AMQ...")
    (reset! queue-count 0)
    (mq/stop-broker! broker)
    (fs/delete-dir mq-dir))
  clojure.lang.Counted
  (count [this] @queue-count))

(defn amq-broker-buffer [broker-name endpoint-name]
  (let [dir (kitchensink/absolute-path broker-name)
        conn-str (str "vm://" broker-name
                      "?broker.useJmx=false"
                      "&broker.enableStatistics=false"
                      "&jms.useCompression=true"
                      "&jms.copyMessageOnSend=false")
        size-megs 20000
        broker (mq/build-embedded-broker broker-name
                                         dir
                                         {:store-usage size-megs
                                          :temp-usage  size-megs})
        _ (println "Using amq in" dir)
        _ (.setPersistent broker false)
        _ (mq/start-broker! broker)
        factory (mq/activemq-connection-factory conn-str)
        connection (doto (.createConnection factory) .start)
        session (.createSession connection true 0)]
    (AMQBrokerBuffer. broker
                      session
                      (.createProducer session (.createQueue session endpoint-name))
                      (.createConsumer session (.createQueue session endpoint-name))
                      dir
                      (atom 0))))

(defn relay-messages-at-rate
  "Relay messages from in-ch to out-ch at the given rate in
  messages-per-second."
  [in-ch out-ch messages-per-second]
  (let [ms-per-message (- (/ 1000 messages-per-second) 3)]
    (go-loop []
      (let [message (<! in-ch)
            message-timeout (timeout (int (+ (rand) ms-per-message)))]
        (when message
          (>! out-ch message)
          (<! message-timeout)
          (recur))))))

(defn benchmark-main
  [& args]
  (let [{:keys [config rand-perc numhosts nummsgs threads] :as options} (validate-cli! args)
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
        hosts (map make-host (range numhosts))
        command-send-ch (if nummsgs
                          (chan)
                          (chan (dropping-buffer 10000)))
        rate-monitor-ch (chan (* 2 threads))
        run-interval (-> (get options :runinterval 30) time/minutes)
        simulation-threads 4
        commands-per-puppet-run (+ (if catalogs 1 0)
                                   (if reports 1 0)
                                   (if facts 1 0))]

    (start-rate-monitor rate-monitor-ch run-interval commands-per-puppet-run)

    (dotimes [_ threads]
      (start-command-sender base-url command-send-ch rate-monitor-ch))

    (when-not catalogs (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports (log/info "No reports specified; skipping report submission"))
    (when-not facts (log/info "No facts specified; skipping fact submission"))

    (if nummsgs
      (do
        (submit-n-messages hosts nummsgs rand-perc command-send-ch)
        (dotimes [_ threads]
          (>!! command-send-ch :stop))
        (close! command-send-ch))

      (let [close-to-stop-ch (chan)
            mq (chan (amq-broker-buffer "benchmark-mq" "benchmark-endpoint"))]

        ;; be sure to clear the temp queue on exit
        (.addShutdownHook (Runtime/getRuntime) (Thread. #(close! mq)))

        ;; initial queue population
        (future
          (println "Populating the queue in the background")
          (dotimes [n numhosts]
            (let [h (-> (make-host n)
                        (assoc :lastrun (rand-lastrun run-interval)))]
              (>!! mq h)))

          (println "... finished filling the queue, continuing with the simulation."))

        (let [run-interval-minutes (time/in-minutes run-interval)
              hosts-per-second (/ numhosts (* run-interval-minutes 60))
              rate-limited-mq-out (chan)]
          (relay-messages-at-rate mq rate-limited-mq-out hosts-per-second)
          (pipeline simulation-threads
                    mq
                    (map #(update-host % rand-perc (time/now) command-send-ch))
                    rate-limited-mq-out)
          (go
            (<! close-to-stop-ch)
            (mapv close! [rate-limited-mq-out mq rate-monitor-ch command-send-ch])))
        close-to-stop-ch))))

(defn chan? [x]
  (satisfies? clojure.core.async.impl.protocols/Channel x))


(defn -main [& args]
  (let [x (apply benchmark-main args)]
    (when (chan? x)
      (println "Press ctrl-c to stop.")
      (<!! x))))
