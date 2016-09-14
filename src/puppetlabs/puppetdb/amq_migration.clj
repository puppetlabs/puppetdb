(ns puppetlabs.puppetdb.amq-migration
  (:import [javax.jms
            ExceptionListener
            JMSException
            MessageListener
            Session
            TextMessage
            BytesMessage
            ConnectionFactory
            Connection
            MessageConsumer
            Queue
            Message]
           [java.io File ByteArrayInputStream]
           [org.apache.activemq.broker BrokerService]
           [org.apache.activemq.usage SystemUsage MemoryUsage]
           [org.apache.activemq.pool PooledConnectionFactory]
           [org.apache.activemq ActiveMQConnectionFactory ScheduledMessage])
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.kitchensink.core :as ks]
            [schema.core :as s]))

(def cli-description "Transfer messages from ActiveMQ to Stockpile")

(defn mq-dir [config]
  (str (io/file (get-in config [:global :vardir]) "mq")))

(defn ^Session create-session [^Connection connection]
  (.createSession connection false Session/CLIENT_ACKNOWLEDGE))

(defn ^Queue create-queue [^Session session endpoint]
  (.createQueue session endpoint))

(defn ^Connection create-connection [^ConnectionFactory factory]
  (doto (.createConnection factory)
    (.setExceptionListener
     (reify ExceptionListener
       (onException [this ex]
         (log/error ex (i18n/trs "receiver queue connection error")))))
    (.start)))

(defn extract-headers
  "Creates a map of custom headers included in `message`, currently only
  supports String headers."
  [^Message msg]
  (into {}
        (for [k (enumeration-seq (.getPropertyNames msg))]
          [(keyword k) (.getStringProperty msg k)])))

(defprotocol ConvertMessageBody
  (convert-message-body ^String [msg]))

(extend-protocol ConvertMessageBody
  TextMessage
  (convert-message-body [msg]
    (-> msg
        .getText
        (.getBytes "UTF-8")))
  BytesMessage
  (convert-message-body [msg]
    (let [len (.getBodyLength msg)
          buf (byte-array len)
          n (.readBytes msg buf)]
      (when (not= len n)
        (throw (Exception. (i18n/trs "Only read {0}/{1} bytes from incoming message" n len))))
      buf)))

(defn coerce-clj->json-byte-stream
  "Converts clojure data to JSON serialized bytes on a
  ByteArrayInputStream. Cheshire will only output to writers and
  stockpile will only accept input streams, so some conversion needs
  to be done in this fn"
  [payload]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (with-open [osw (java.io.OutputStreamWriter. baos java.nio.charset.StandardCharsets/UTF_8)]
      (json/generate-stream payload osw))
    (-> baos
        .toByteArray
        java.io.ByteArrayInputStream.)))

(defn convert-old-command-format
  "Converts from the command format that didn't include the certname
  in the toplevel key list and had all the command data with the
  payload key"
  [message-bytes]
  (let [{:keys [command version] :as old-command} (json/parse-strict message-bytes true)]
    {:command command
     :version (Long/parseLong version)
     :certname (or (get-in old-command [:payload :certname])
                   (get-in old-command [:payload :name]))
     :payload (coerce-clj->json-byte-stream (:payload old-command))}))

(def upgrade-message-schema
  {:command s/Str
   :version s/Int
   :certname s/Str
   :payload java.io.ByteArrayInputStream})

(s/defn coerce-to-new-command :-  upgrade-message-schema
  "Commands coming out of this function are suitable for enqueuing
  into stockpile. They must include command, version, certname and a
  payload"
  [{:keys [command version certname] :as headers} message-bytes]
  (if (and command version certname)
    {:command command
     :version (Long/parseLong version)
     :certname certname
     :payload (java.io.ByteArrayInputStream. message-bytes)}
    (convert-old-command-format message-bytes)))

(defn discard-amq-message [message-bytes id headers exception dlo]
  (let [now (ks/timestamp)]
    (dlo/discard-bytes message-bytes
                       id
                       (or (:received headers)
                           now)
                       [{:exception exception
                         :time now}]
                       dlo)))

(defn create-message-processor
  [enqueue-fn dlo]
  (fn [id ^Message message]
    ;; When the queue is shutting down, it sends nil message
    (when message
      (let [headers (extract-headers message)
            message-bytes (convert-message-body message)]
        (try
          (let [{:keys [command version certname payload] :as msg} (coerce-to-new-command headers message-bytes)]
            (try
              (enqueue-fn command version certname payload)
              (catch Exception ex
                (log/error ex (i18n/trs "[{0}] [{1}] [{2}] Unable to process message"
                                        command version certname))
                (discard-amq-message message-bytes id headers ex dlo))))
          (catch AssertionError ex
            (log/error ex (i18n/trs "Unable to process message: {0}" id))
            (discard-amq-message message-bytes id headers ex dlo))
          (catch Exception ex
            (log/error ex (i18n/trs "Unable to process message: {0}" id))
            (discard-amq-message message-bytes id headers ex dlo))
          (finally
            (.acknowledge message)))))))

(defn consume-everything
  "Attempts to read all available messages from ActiveMQ, giving up
  after 5 seconds if one is not available"
  [^MessageConsumer consumer process-message id]
  (try
    (loop []
      (when-let [msg (.receive consumer 5000)]
        (process-message (swap! id inc) msg)
        (recur)))
    (catch javax.jms.IllegalStateException e
      (log/info (i18n/trs "Received IllegalStateException, shutting down")))
    (catch Exception e
      (log/error e))))

(defn create-mq-receiver
  [^ConnectionFactory conn-pool endpoint process-message id]
  (with-open [connection (create-connection conn-pool)
              session (create-session connection)
              consumer (.createConsumer session (create-queue session endpoint))]
    (log/info (i18n/trs "Processing messages from the Queue: {0}" endpoint))
    (consume-everything consumer process-message id)))

(defn create-scheduler-receiver
  "Drains the scheduler queue (where PuppetDB commands are retried)
  and enqueues them into the normal queue"
  [^ConnectionFactory conn-pool endpoint process-message dlo id]
  (with-open [connection (create-connection conn-pool)
              session (create-session connection)]
    (let [request-browse (.createTopic session ScheduledMessage/AMQ_SCHEDULER_MANAGEMENT_DESTINATION)
          browse-dest (.createTemporaryQueue session)]
      (with-open [producer (.createProducer session request-browse)
                  consumer (.createConsumer session browse-dest)]
        (let [request (doto (.createMessage session)
                        (.setStringProperty ScheduledMessage/AMQ_SCHEDULER_ACTION
                                            ScheduledMessage/AMQ_SCHEDULER_ACTION_BROWSE)
                        (.setJMSReplyTo browse-dest))
              process-message-and-remove (fn [^Message msg]
                                           (try
                                             (process-message msg)
                                             (let [msg-id (.getStringProperty msg
                                                                              ScheduledMessage/AMQ_SCHEDULED_ID)
                                                   remove (doto (.createMessage session)
                                                            (.setStringProperty ScheduledMessage/AMQ_SCHEDULER_ACTION
                                                                                ScheduledMessage/AMQ_SCHEDULER_ACTION_REMOVE)
                                                            (.setStringProperty ScheduledMessage/AMQ_SCHEDULED_ID msg-id))]
                                               (.send producer remove))
                                             (catch Exception e
                                               (discard-amq-message (convert-message-body msg) (swap! id inc) {} e dlo))))]
          (.send producer request)
          (Thread/sleep 2000)
          (log/info (i18n/trs "Processing messages from the scheduler"))
          (consume-everything consumer process-message-and-remove id))))))

(def default-mq-endpoint "puppetlabs.puppetdb.commands")
(def retired-mq-endpoint "com.puppetlabs.puppetdb.commands")

(defn- set-usage!
  "Internal helper function for setting `SystemUsage` values on a `BrokerService`
  instance.

  `broker`    - the `BrokerService` instance
  `megabytes` - the value to set as the limit for the desired `SystemUsage` setting
  `usage-fn`  - a function that accepts a `SystemUsage` instance and returns
  the child object whose limit we are configuring.
  `desc`      - description of the setting we're configuring, to be used in a log message
  "
  [^BrokerService broker
   megabytes
   usage-fn
   ^String desc]
  {:pre [((some-fn nil? integer?) megabytes)
         (fn? usage-fn)]}
  (when-let [limit (some-> megabytes (* 1024 1024))]
    (log/info (i18n/trs "Setting ActiveMQ {0} limit to {1} MB" desc megabytes))
    (-> (.getSystemUsage broker)
        usage-fn
        (.setLimit limit)))
  broker)

(defn ^BrokerService build-embedded-broker
  "Configures an embedded, persistent ActiveMQ broker.

  `name` - What to name to queue. As this is an embedded broker, the
  full name will be of the form 'vm://foo' where 'foo' is the name
  you've supplied. That is the full URI you should use for
  establishing connections to the broker.

  `dir` - What directory in which to store the broker's data files. It
  will be created if it doesn't exist.

  `config` - an optional map containing configuration values for initializing
  the broker.  Currently supported options:

  :store-usage  - the maximum amount of disk usage to allow for persistent messages, or `nil` to use the default value of 100GB.
  :temp-usage   - the maximum amount of disk usage to allow for temporary messages, or `nil` to use the default value of 50GB.
  :memory-usage - the maximum amount of disk usage to allow for persistent messages, or `nil` to use the default value of 1GB.
  "
  [^String name ^String dir {:keys [memory-usage store-usage temp-usage] :as config}]
  {:pre [(map? config)]}
  (let [mq (doto (BrokerService.)
             (.setUseShutdownHook false)
             (.setBrokerName name)
             (.setDataDirectory dir)
             (.setSchedulerSupport true)
             (.setPersistent true)
             (.setUseJmx false)
             (set-usage! memory-usage #(.getMemoryUsage %) "MemoryUsage")
             (set-usage! store-usage #(.getStoreUsage %) "StoreUsage")
             (set-usage! temp-usage #(.getTempUsage %) "TempUsage"))
        mc (doto (.getManagementContext mq)
             (.setCreateConnector false))
        db (doto (.getPersistenceAdapter mq)
             (.setIgnoreMissingJournalfiles true)
             (.setArchiveCorruptedIndex true)
             (.setCheckForCorruptJournalFiles true)
             (.setChecksumJournalFiles true))]
    mq))

(defn ^BrokerService start-broker!
  "Starts up the supplied broker, making it ready to accept
  connections."
  [^BrokerService broker]
  (doto broker
    (.start)
    (.waitUntilStarted)))

(defn stop-broker!
  "Stops the supplied broker"
  [^BrokerService broker]
  (doto broker
    (.stop)
    (.waitUntilStopped)))

(defn ^BrokerService retry-build-and-start-broker!
  [brokername dir config]
  (try
    (start-broker! (build-embedded-broker brokername dir config))
    (catch java.io.EOFException e
      (log/error e (i18n/trs "EOF Exception caught during broker start. This might be due to KahaDB corruption. Consult the PuppetDB troubleshooting guide."))
      (throw e))))

(defn ^BrokerService build-and-start-broker!
  "Builds ands starts a broker in one go, attempts restart upon known exceptions"
  [brokername dir config]
  (try
    (try
      (start-broker! (build-embedded-broker brokername dir config))
      (catch java.io.EOFException e
        (log/warn (i18n/trs "Caught EOFException on broker startup, trying again. This is probably due to KahaDB corruption (see \"KahaDB Corruption\" in the PuppetDB manual)."))
        (retry-build-and-start-broker! brokername dir config)))
    (catch java.io.IOException e
      (throw (java.io.IOException.
              (i18n/trs "Unable to start broker in {0}. This is probably due to KahaDB corruption or version incompatibility after a PuppetDB downgrade (see \"KahaDB Corruption\" in the PuppetDB manual)." (pr-str dir))
              e)))))

(defn activemq-connection-factory [spec]
  (proxy [PooledConnectionFactory java.lang.AutoCloseable]
      [(ActiveMQConnectionFactory. spec)]
    (close [] (.stop this))))

(defn upgrade-lockfile-path [vardir]
  (io/file vardir "mq-migrated"))

(defn needs-upgrade?
  "Returns true if the user has an unmigrated ActiveMQ director in
  their vardir"
  [config]
  (let [vardir (get-in config [:global :vardir])]
    (and (not (fs/exists? (upgrade-lockfile-path vardir)))
         (fs/exists? (io/file vardir "mq")))))

(defn lock-upgrade
  "Puts a lockfile in vardir to indicate the user has already migrated
  to Stockpile"
  [config]
  (fs/touch (upgrade-lockfile-path (get-in config [:global :vardir]))))

(defn activemq->stockpile
  "Drains the queue found in `cmd-proc-config` and uses `enqueue-fn`
  to resubmit those commands to PuppetDB (running Stockpile). All
  failures to enqueue are discarded using the `dlo`"
  [{global-config :global
    {mq-config :mq :as cmd-proc-config} :command-processing}
   enqueue-fn
   dlo]
  ;; Starting
  (let [mq-dir (str (io/file (:vardir global-config) "mq"))
        mq-broker-url (format "%s&wireFormat.maxFrameSize=%s&marshal=true"
                              (:address mq-config "vm://localhost?jms.prefetchPolicy.all=1&create=false")
                              (:max-frame-size cmd-proc-config 209715200))
        mq-discard-dir (str (io/file mq-dir "discard"))
        mq-endpoint (:endpoint mq-config default-mq-endpoint)
        process-message (create-message-processor enqueue-fn dlo)
        id (atom 0)]
    (try
      (let [conn-pool (activemq-connection-factory mq-broker-url)]
        (try
          (let [broker (do (log/info (i18n/trs "Starting broker"))
                           (build-and-start-broker! "localhost" mq-dir cmd-proc-config))]
            (try
              ;; Drain the scheduler so we don't get any extra messages on the Queue when
              ;; we're processing
              (create-scheduler-receiver conn-pool mq-endpoint process-message dlo id)
              (create-mq-receiver conn-pool mq-endpoint process-message id)
              (create-mq-receiver conn-pool retired-mq-endpoint process-message id)

              (log/info (i18n/trs "You may safely delete {0}" mq-dir))

              (catch Exception e
                (log/error e (i18n/trs "Unable to receive ActiveMQ messages. Migration of existing messages failed.")))
              (finally
                (stop-broker! broker))))
          (catch Exception e
            (log/error e (i18n/trs "Unable to start ActiveMQ broker. Migration of existing messages failed.")))
          (finally
            (.stop conn-pool))))
      (catch Exception e
        (log/error e (i18n/trs "Unable to connect to ActiveMQ. Migration of existing messages failed."))))))
