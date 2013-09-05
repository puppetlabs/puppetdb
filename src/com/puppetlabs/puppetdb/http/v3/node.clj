(ns com.puppetlabs.puppetdb.http.v3.node
  (:require [com.puppetlabs.puppetdb.http.v2.node :as v2-node])
  (:use [com.puppetlabs.middleware :only (verify-accepts-json wrap-with-paging-options)]))

(def node-app
  (-> v2-node/routes
    (verify-accepts-json)
    (wrap-with-paging-options)))
