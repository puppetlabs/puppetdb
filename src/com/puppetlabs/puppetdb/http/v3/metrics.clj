(ns com.puppetlabs.puppetdb.http.v3.metrics
  (:require  [com.puppetlabs.puppetdb.http.v2.metrics :as v2-metrics]))

(def metrics-app
  v2-metrics/metrics-app)
