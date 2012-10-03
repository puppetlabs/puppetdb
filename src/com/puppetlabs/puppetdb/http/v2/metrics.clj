(ns com.puppetlabs.puppetdb.http.v2.metrics
  (:require  [com.puppetlabs.puppetdb.http.v1.metrics :as v1-metrics]))

(def metrics-app
  v1-metrics/metrics-app)
