;; ## Message Queue utilities
;;
(ns com.puppetlabs.mq
  (:import (org.apache.activemq.broker BrokerService))
  (:require [clamq.activemq :as activemq]
            [clamq.protocol.connection :as mq-conn]
            [clamq.protocol.consumer :as mq-consumer]
            [clamq.protocol.seqable :as mq-seq]
            [clamq.protocol.producer :as mq-producer]))

(defn build-embedded-broker
  "Configures an embedded, persistent ActiveMQ broker.

  `name` - What to name to queue. As this is an embedded broker, the
  full name will be of the form 'vm://foo' where 'foo' is the name
  you've supplied. That is the full URI you should use for
  establishing connections to the broker.

  `dir` - What directory in which to store the broker's data files. It
  will be created if it doesn't exist."
  ([dir]
     {:pre  [(string? dir)]
      :post [(instance? BrokerService %)]}
     (build-embedded-broker "localhost" dir))
  ([name dir]
     {:pre  [(string? name)
             (string? dir)]
      :post [(instance? BrokerService %)]}
     (doto (BrokerService.)
       (.setBrokerName name)
       (.setDataDirectory dir)
       (.setPersistent true))))

(defn start-broker!
  "Starts up the supplied broker, making it ready to accept
  connections."
  [^BrokerService broker]
  (.start broker)
  (.waitUntilStarted broker))

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

(defn drain-into-vec!
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
  (with-open [consumer (mq-conn/seqable connection {:endpoint endpoint :timeout timeout})]
    (reduce into []
            (map #(do (mq-seq/ack consumer) [%1])
                 (mq-seq/mseq consumer)))))
