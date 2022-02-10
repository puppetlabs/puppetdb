(ns puppetlabs.puppetdb.mq
  (:require
   [clojure.java.jmx :as jmx]
   [metrics.timers :refer [timer]]
   [puppetlabs.puppetdb.metrics.core :as metrics]))

(def mq-metrics-registry (get-in metrics/metrics-registries [:mq :registry]))

(def metrics (atom {:message-persistence-time (timer mq-metrics-registry
                                                     (metrics/keyword->metric-name
                                                       [:global] :message-persistence-time))}))

(defn queue-size
  "Returns the number of pending messages in the queue.
  Throws {:kind ::queue-not-found} when the queue doesn't exist."
  []
  (try
    (jmx/read "puppetlabs.puppetdb.mq:name=global.depth" :Count)
    (catch javax.management.InstanceNotFoundException ex
      (throw (ex-info "" {:kind ::queue-not-found})))))
