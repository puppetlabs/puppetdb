;; ## Message Queue utilities
;;
(ns com.puppetlabs.mq
  (:import [org.apache.activemq.broker BrokerService]
           [org.apache.activemq ScheduledMessage]
           [org.apache.activemq.usage SystemUsage]
           [javax.jms Message TextMessage BytesMessage ExceptionListener MessageListener]
           [org.apache.activemq ActiveMQConnectionFactory]
           [org.springframework.jms.connection CachingConnectionFactory]
           [org.springframework.jms.listener DefaultMessageListenerContainer])
  (:require [com.puppetlabs.cheshire :as json]
            [clamq.activemq :as activemq]
            [clamq.protocol.connection :as mq-conn]
            [clamq.protocol.consumer :as mq-consumer]
            [clamq.protocol.seqable :as mq-seq]
            [clamq.protocol.producer :as mq-producer]
            [clojure.tools.logging :as log]
            [clamq.protocol.consumer :as consumer]
            [clamq.jms :as jms])
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

(defn build-and-start-broker!
  "Builds ands starts a broker in one go, attempts restart upon known exceptions"
  [brokername dir config]
  (try
    (start-broker! (build-embedded-broker brokername dir config))
    (catch java.io.EOFException e
      (log/warn
       "Caught EOFException on broker startup, trying again."
       "This is probably due to KahaDB corruption"
       "(see \"KahaDB Corruption\" in the PuppetDB manual).")
      (start-broker! (build-embedded-broker brokername dir config)))
    (catch java.io.IOException e
      (throw (java.io.IOException.
              (str "Unable to start broker in " (str (pr-str dir) ".")
                   " This is probably due to KahaDB corruption"
                   " or version incompatibility after a PuppetDB downgrade"
                   " (see \"KahaDB Corruption\" in the PuppetDB manual)."))
             e))))

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
    (while (> limit (count @contents))
      (Thread/sleep 10))

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

(defn convert-message
  "Convert the given `message` to a string using the type-specific method."
  [^Message message]
  (cond
   (instance? javax.jms.TextMessage message)
   (let [^TextMessage text-message message]
     (.getText text-message))
   (instance? javax.jms.BytesMessage message)
   (let [^BytesMessage bytes-message message]
     (.readUTF8 bytes-message))
   :else
   (throw (ex-info (str "Expected a text message, instead found: " (class message))))))

(defn extract-headers
  "Creates a map of custom headers included in `message`, currently only
   supports String headers."
  [^Message msg]
  (reduce (fn [acc k]
            (assoc acc
              (keyword k)
              (.getStringProperty msg k)))
          {} (enumeration-seq (.getPropertyNames msg))))

(defn create-message-listener
  "Creates an implementation of the MessageListener interface needed by JMS for
   consuming messages. This is based on a related clamq macro, but includes all
   message headers"
  [handler-fn failure-fn limit container]
  (let [counter (atom 0)]
    (reify MessageListener
      (onMessage [this message]
        (let [converted-message (convert-message message)]
          (swap! counter inc)
          (try
            (handler-fn {:headers (extract-headers message)
                         :body converted-message})
            (catch Exception ex
              (failure-fn {:message converted-message :exception ex}))
            (finally
              (when (= limit @counter)
                (do (.stop container)
                    (future (.shutdown container)))))))))))


(defn message-consumer
  "Instantiates the MQ listening container along with the
   correct message listener"
  [connection {endpoint :endpoint
               handler-fn :on-message
               transacted :transacted
               pubSub :pubSub
               limit :limit
               failure-fn :on-failure
               :or {pubSub false
                    limit 0}}]
  (let [container (DefaultMessageListenerContainer.)
        listener (create-message-listener handler-fn failure-fn limit container)]
    (doto container
      (.setConnectionFactory connection)
      (.setDestinationName endpoint)
      (.setMessageListener listener)
      (.setSessionTransacted transacted)
      (.setPubSubDomain pubSub)
      (.setConcurrentConsumers 1))
    (reify consumer/Consumer
      (start [self] (do
                      (doto container
                        (.start)
                        (.initialize))
                        nil))
      (close [self] (do
                      (.shutdown container)
                      nil)))))

(defn wrap-connection
  "Provides a shim between the clamq JMS implementation and it's usage.
   Most functions delegate directly to clamq ones, but the consumer function
   uses the above functions instead to include all the headers in the message"
  [conn-factory shutdown-fn]
  (let [jms-conn (jms/jms-connection conn-factory shutdown-fn)]
    (reify mq-conn/Connection
      (producer [self]
        (mq-conn/producer jms-conn))
      (producer [self conf]
        (mq-conn/producer jms-conn conf))
      (consumer [self conf]
        (message-consumer conn-factory conf))
      (seqable [self conf]
        (mq-conn/seqable jms-conn conf))
      (close [self]
        (mq-conn/close jms-conn)))))

(defn activemq-connection [broker]
  "Returns an ActiveMQ connection wrapped in our connection protocol shim"
  (let [pool (-> broker
                 ActiveMQConnectionFactory.
                 CachingConnectionFactory.)]
    (wrap-connection pool #(.destroy pool))))
