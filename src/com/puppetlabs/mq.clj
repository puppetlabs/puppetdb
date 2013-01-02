;; ## Message Queue utilities
;;
(ns com.puppetlabs.mq
  (:import [org.apache.activemq.broker BrokerService]
           [org.apache.activemq ScheduledMessage]
           [org.apache.activemq.usage SystemUsage])
  (:require [cheshire.core :as json]
            [clamq.activemq :as activemq]
            [clamq.protocol.connection :as mq-conn]
            [clamq.protocol.consumer :as mq-consumer]
            [clamq.protocol.seqable :as mq-seq]
            [clamq.protocol.producer :as mq-producer]
            [clojure.tools.logging :as log])
  (:use [cheshire.custom :only (JSONable)]))

(defn- set-usage!*
  "Internal helper function for setting `SystemUsage` values on a `BrokerService`
  instance.

  `broker`    - the `BrokerService` instance
  `megabytes` - the value to set as the limit for the desired `SystemUsage` setting
  `usage-fn`  - a function that accepts a `SystemUsage` instance and returns
                the child object whose limit we are configuring.
  `desc`      - description of the setting we're configuring, to be used in a log message
  "
  [broker megabytes usage-fn desc]
  {:pre  [(instance? BrokerService broker)
          ((some-fn nil? integer?) megabytes)
          (fn? usage-fn)
          (string? desc)]}
  (when megabytes
    (log/info "Setting ActiveMQ " desc " limit to " megabytes " MB")
    (-> broker
      (.getSystemUsage)
      (usage-fn)
      (.setLimit (* megabytes 1024 1024))))
  broker)

(defn- set-store-usage!
   "Configures the `StoreUsage` setting for an instance of `BrokerService`.

   `broker`     - the `BrokerService` to configure
   `megabytes ` - the maximum amount of disk usage to allow for persistent messages,
                  or `nil` to use the default value of 100GB.

   Returns the (potentially modified) `broker` object."
  [broker megabytes]
  (set-usage!* broker megabytes #(.getStoreUsage %) "StoreUsage"))

(defn- set-temp-usage!
  "Configures the `TempUsage` setting for an instance of `BrokerService`.

  `broker`     - the `BrokerService` to configure
  `megabytes ` - the maximum amount of disk usage to allow for temporary messages,
                 or `nil` to use the default value of 50GB.

  Returns the (potentially modified) `broker` object."
  [broker megabytes]
  (set-usage!* broker megabytes #(.getTempUsage %) "TempUsage"))

(defn build-embedded-broker
  "Configures an embedded, persistent ActiveMQ broker.

  `name` - What to name to queue. As this is an embedded broker, the
  full name will be of the form 'vm://foo' where 'foo' is the name
  you've supplied. That is the full URI you should use for
  establishing connections to the broker.

  `dir` - What directory in which to store the broker's data files. It
  will be created if it doesn't exist.

  `config` - an optional map containing configuration values for initializing
  the broker.  Currently supported options:

      :store-usage  - sets the limit of disk storage (in megabytes) for persistent messages
      :temp-usage   - sets the limit of disk storage in the broker's temp dir
                      (in megabytes) for temporary messages"
  ([dir]
     {:pre  [(string? dir)]
      :post [(instance? BrokerService %)]}
     (build-embedded-broker "localhost" dir))
  ([name dir]
     {:pre  [(string? name)
             (string? dir)]
      :post [(instance? BrokerService %)]}
     (build-embedded-broker name dir {}))
  ([name dir config]
    {:pre   [(string? name)
             (string? dir)
             (map? config)]
     :post  [(instance? BrokerService %)]}
    (let [mq (doto (BrokerService.)
               (.setBrokerName name)
               (.setDataDirectory dir)
               (.setSchedulerSupport true)
               (.setPersistent true)
               (set-store-usage! (:store-usage config))
               (set-temp-usage!  (:temp-usage config)))
          mc (doto (.getManagementContext mq)
               (.setCreateConnector false))
          db (doto (.getPersistenceAdapter mq)
               (.setIgnoreMissingJournalfiles true)
               (.setArchiveCorruptedIndex true)
               (.setCheckForCorruptJournalFiles true)
               (.setChecksumJournalFiles true))]
      mq)))

(defn start-broker!
  "Starts up the supplied broker, making it ready to accept
  connections."
  [^BrokerService broker]
  (.start broker)
  (.waitUntilStarted broker)
  broker)

(defn stop-broker!
  "Stops the supplied broker"
  [^BrokerService broker]
  (.stop broker)
  (.waitUntilStopped broker))

(defn connect!
  "Connect to the specified broker URI."
  [uri]
  {:pre [(string? uri)]}
  (activemq/activemq-connection uri))

(defn connect-and-publish!
  "Construct an MQ producer and send the indicated message.

  This function take the same arguments as
  `clamq.protocol.producer/publish`, with an additional initial
  argument of an active MQ connection"
  [connection & args]
  (let [producer (mq-conn/producer connection)]
    (apply mq-producer/publish producer args)))

(defn publish-json!
  "Publish the `msg` to the queue identified by `endpoint` using an MQ
  handle `connection`."
  [connection endpoint msg]
  {:pre [(string? endpoint)
         (satisfies? JSONable msg)]}
  (connect-and-publish! connection endpoint (json/generate-string msg)))

(defn timed-drain-into-vec!
  "Drains the indicated MQ endpoint into a vector

  `connection` - established MQ connection

  `endpoint` - which MQ endpoint you wish to drain

  `timeout` - how many millis to wait for an incoming message before
  we consider the endpoint drained."
  [connection endpoint timeout]
  {:pre  [(string? endpoint)
          (integer? timeout)
          (pos? timeout)]
   :post [(vector? %)]}
  (let [contents (atom [])
        mq-error (promise)
        consumer (mq-conn/consumer connection
                                   {:endpoint   endpoint
                                    :transacted true
                                    :on-message #(swap! contents conj %)
                                    :on-failure #(deliver mq-error (:exception %))})]
    (mq-consumer/start consumer)
    (deref mq-error timeout nil)
    (mq-consumer/close consumer)
    (if (realized? mq-error)
      (throw @mq-error)
      @contents)))

(defn bounded-drain-into-vec!
  "Drains N messages from the indicated MQ endpoint into a vector

  `connection` - established MQ connection

  `endpoint` - which MQ endpoint you wish to drain

  `limit` - block until this many message have been received."
  [connection endpoint limit]
  {:pre  [(string? endpoint)
          (integer? limit)
          (pos? limit)]
   :post [(vector? %)
          (= limit (count %))]}
  (let [contents (atom [])
        mq-error (promise)
        consumer (mq-conn/consumer connection
                                   {:endpoint   endpoint
                                    :transacted true
                                    :on-message #(swap! contents conj %)
                                    :on-failure #(deliver mq-error (:exception %))})]
    (mq-consumer/start consumer)
    (loop []
      (when (> limit (count @contents))
        (Thread/sleep 10)
        (recur)))

    (mq-consumer/close consumer)
    (if (realized? mq-error)
      (throw @mq-error)
      (vec (take limit @contents)))))

(defn delay-property
  "Returns an ActiveMQ property map indicating a message should be
  published only after a delay. The following invokations are
  equivalent:

    (delay-property 3600000)
    (delay-property 1 :hours)
  "
  ([number unit]
     (condp = unit
       :seconds (delay-property (* 1000 number))
       :minutes (delay-property (* 60 1000 number))
       :hours   (delay-property (* 60 60 1000 number))
       :days    (delay-property (* 24 60 60 1000 number))))
  ([millis]
     {:pre  [(number? millis)
             (pos? millis)]
      :post [(map? %)]}
     {ScheduledMessage/AMQ_SCHEDULED_DELAY (str (long millis))}))
