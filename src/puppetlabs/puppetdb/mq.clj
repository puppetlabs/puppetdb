(ns puppetlabs.puppetdb.mq
  (:import [org.apache.activemq.broker BrokerService]
           [org.apache.activemq ScheduledMessage]
           [org.apache.activemq.usage SystemUsage MemoryUsage]
           [javax.jms Connection Message TextMessage BytesMessage Session]
           [org.apache.activemq ActiveMQConnectionFactory]
           [org.apache.activemq.pool PooledConnectionFactory])
  (:require [clojure.java.jmx :as jmx]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [schema.core :as s]
            [slingshot.slingshot :refer [throw+]]
            [metrics.timers :refer [timer time!]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.i18n.core :refer [trs]]))

(def mq-metrics-registry (get-in metrics/metrics-registries [:mq :registry]))

(def metrics (atom {:message-persistence-time (timer mq-metrics-registry
                                                     (metrics/keyword->metric-name
                                                       [:global] :message-persistence-time))}))

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
    (log/info (trs "Setting ActiveMQ {0} limit to {1} MB" desc megabytes))
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

(defn- set-memory-usage!
  "Configures the `MemoryUsage` setting for an instance of `BrokerService`.

  `broker`     - the `BrokerService` to configure
  `megabytes ` - the maximum amount of memory usage to allow for the
  BrokerService, or `nil` to use the default value of 1GB.

  Returns the (potentially modified) `broker` object."
  [broker megabytes]
  (set-usage!* broker megabytes #(.getMemoryUsage %) "MemoryUsage"))

(defn- set-temp-usage!
  "Configures the `TempUsage` setting for an instance of `BrokerService`.

  `broker`     - the `BrokerService` to configure
  `megabytes ` - the maximum amount of disk usage to allow for temporary messages,
  or `nil` to use the default value of 50GB.

  Returns the (potentially modified) `broker` object."
  [broker megabytes]
  (set-usage!* broker megabytes #(.getTempUsage %) "TempUsage"))

(defn ^:dynamic enable-jmx
  "This function exists to enable starting multiple PuppetDB instances
  inside a single JVM. Starting up a second instance results in a
  collision exception between JMX beans from the two
  instances. Disabling JMX from the broker avoids that issue"
  [broker should-enable?]
  (.setUseJmx broker should-enable?))

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
              (.setUseShutdownHook false)
              (.setBrokerName name)
              (.setDataDirectory dir)
              (.setSchedulerSupport true)
              (.setPersistent true)
              (enable-jmx true)
              (set-memory-usage! (:memory-usage config))
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
       (str
        (trs "Caught EOFException on broker startup, trying again.")
        " "
        (trs "This is probably due to KahaDB corruption (see \"KahaDB Corruption\" in the PuppetDB manual).")))
      (start-broker! (build-embedded-broker brokername dir config)))
    (catch java.io.IOException e
      (throw (java.io.IOException.
              (str
               (trs
                "Unable to start broker in {0}." (str (pr-str dir)))
               " "
               (trs "This is probably due to KahaDB corruption or version incompatibility after a PuppetDB downgrade (see \"KahaDB Corruption\" in the PuppetDB manual)."))
              e)))))

(defn extract-headers
  "Creates a map of custom headers included in `message`, currently only
  supports String headers."
  [^Message msg]
  (reduce (fn [acc k]
            (assoc acc
              (keyword k)
              (.getStringProperty msg k)))
          {} (enumeration-seq (.getPropertyNames msg))))

(defn convert-message-body
  "Convert the given `message` to a string using the type-specific method."
  [^Message message]
  (cond
   (instance? javax.jms.TextMessage message)
   (let [^TextMessage text-message message]
     (.getText text-message))
   (instance? javax.jms.BytesMessage message)
   (let [^BytesMessage bytes-message message
         len (.getBodyLength message)
         buf (byte-array len)
         n (.readBytes bytes-message buf)]
     (when (not= len n)
       (throw (Exception. (trs "Only read {0}/{1} bytes from incoming message"
                               n len))))
     (String. buf "UTF-8"))
   :else
   (throw (Exception. (trs "Expected TextMessage or BytesMessage; found {0}"
                           (class message))))))

(defn convert-jms-message [m]
  {:headers (extract-headers m) :body (convert-message-body m)})

(def jms-message-schema
  {(s/required-key :headers) {s/Keyword s/Str}
   (s/required-key :body) s/Str})

(defprotocol JmsMessagePropertySetter
  (-set-jms-property! [value name message]
    "Sets the named JMS message property to value."))

(extend String
  JmsMessagePropertySetter
  {:-set-jms-property! (fn [value name message]
                         (.setStringProperty message name value))})

(defn set-jms-property!
  "Sets the named JMS message property to value."
  [message name value]
  (-set-jms-property! value name message))

(defprotocol ToJmsMessage
  (-to-jms-message [x properties session]
    "Converts x to a JMSMessage with the given properties via sesson."))

(extend String
  ToJmsMessage
  {:-to-jms-message (fn [^String x properties ^Session session]
                      (let [msg (.createBytesMessage session)]
                        (.writeBytes msg (.getBytes x "UTF-8"))
                        (doseq [[name value] properties]
                          (-set-jms-property! value name msg))
                        msg))})

(extend utils/byte-array-class
  ToJmsMessage
  {:-to-jms-message (fn [x properties ^Session session]
                      (let [msg (.createBytesMessage session)]
                        (.writeBytes msg x)
                        (doseq [[name value] properties]
                          (-set-jms-property! value name msg))
                        msg))})

(defn to-jms-message
  "Converts x to a JMSMessage with the given properties via session."
  [session x properties]
  (-to-jms-message x properties session))

(defmacro commit-or-rollback
  [session & body]
  `(try (let [result# (do ~@body)]
          (.commit ~session)
          result#)
        (catch Throwable ex# (.rollback ~session) (throw ex#))))

(def connection-schema Connection)

(defn send-message!
  "Sends message with attributes via a new session (sessions are
  single-threaded)."
  ([connection endpoint message]
   (send-message! connection endpoint message {}))
  ([connection endpoint message attributes]
   (time! (get @metrics :message-persistence-time)
          (with-open [s (.createSession connection true 0)
                      pro (.createProducer s (.createQueue s endpoint))]
            (commit-or-rollback s
                                (.send pro (to-jms-message s message attributes)))))))

(defn-validated timed-drain-into-vec! :- [jms-message-schema]
  "Drains messages from the indicated endpoint into a vector via connection.
  Gives up after timeout ms and returns whatever has accumulated at
  that point.  The retrieved messages will have been committed."
  [connection :- connection-schema
   endpoint :- s/Str
   timeout :- (s/constrained s/Int pos?)]
  (with-open [s (.createSession connection true 0)
              consumer (.createConsumer s (.createQueue s endpoint))]
    (let [deadline (+ (System/currentTimeMillis) timeout)
          next-msg #(commit-or-rollback s
                      (let [remaining (- deadline (System/currentTimeMillis))]
                        (when (pos? remaining)
                          (.receive consumer remaining))))]
      (loop [msg (next-msg) result []]
        (if msg
          (recur (next-msg) (conj result (convert-jms-message msg)))
          result)))))

(defn-validated bounded-drain-into-vec! :- [jms-message-schema]
  "Drains n messages from the indicated endpoint into a vector via
  connection.  The retrieved messages will have been committed."
  [connection :- connection-schema
   endpoint :- s/Str
   n :- (s/constrained s/Int pos?)]
  (with-open [s (.createSession connection true 0)
              consumer (.createConsumer s (.createQueue s endpoint))]
    (vec (repeatedly n #(commit-or-rollback s
                          (convert-jms-message (.receive consumer)))))))

(defn- broker-mb
  [name]
  (str "org.apache.activemq:type=Broker,brokerName=" name))

(defn-validated queue-size
  "Returns the number of pending messages in the queue.
  Throws {:type ::queue-not-found} when the queue doesn't exist."
  []
  (try
    (jmx/read "puppetlabs.puppetdb.mq:name=global.depth" :Count)
    (catch javax.management.InstanceNotFoundException ex
      (throw+ {:type ::queue-not-found}))))

(defn-validated transfer-messages!
  "Transfers all of the messages currently available in the named
  source queue to the destination and returns the number transferred.
  Each message successfully transferred will be committed."
  [broker :- s/Str
   source :- s/Str
   destination :- s/Str]
  (jmx/invoke-signature (format "%s,destinationType=Queue,destinationName=%s"
                                (broker-mb broker) source)
                        :moveMatchingMessagesTo
                        ["java.lang.String" "java.lang.String"]
                        "JMSMessageID is NOT NULL"
                        destination))

(defn-validated remove-queue!
  "Deletes the named broker queue.  Does nothing if the queue doesn't exist."
  [broker :- s/Str
   queue :- s/Str]
  (jmx/invoke-signature (broker-mb broker)
                        :removeQueue ["java.lang.String"] queue))

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

(defn activemq-connection-factory [spec]
  (proxy [PooledConnectionFactory java.lang.AutoCloseable]
      [(ActiveMQConnectionFactory. spec)]
    (close [] (.stop this))))
