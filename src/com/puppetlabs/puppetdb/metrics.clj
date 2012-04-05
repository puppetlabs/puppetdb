;; ## Performance utilities
;;
;; Wrapper functions around `metrics-clojure`.
;;
(ns com.puppetlabs.puppetdb.metrics)

;; Reference to underlying static containing all declared metrics.
(def *registry* (com.yammer.metrics.Metrics/defaultRegistry))

(defn all-metrics
  "Returns all declared metrics in a map of `internal-name` to metric
  object"
  []
  (into {} (.allMetrics *registry*)))
