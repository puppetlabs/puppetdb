;; ## Performance utilities
;;
;; Wrapper functions around `metrics-clojure`.
;;
(ns com.puppetlabs.cmdb.metrics
  (:import (com.yammer.metrics.reporting JmxReporter)))

;; Reference to underlying static containing all declared metrics.
(def *registry* (com.yammer.metrics.Metrics/defaultRegistry))

(defn all-metrics
  "Returns all declared metrics in a map of `internal-name` to metric
  object"
  []
  (into {} (.allMetrics *registry*)))

(defn report-to-jmx
  "Starts a background thread that enables JMX reporting of all
  metrics"
  []
  (JmxReporter/startDefault *registry*))
