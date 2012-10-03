(ns com.puppetlabs.puppetdb.http.v2.status
  (:require  [com.puppetlabs.puppetdb.http.v1.status :as v1-status]))

(def status-app
  v1-status/status-app)
