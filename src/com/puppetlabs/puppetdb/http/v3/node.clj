(ns com.puppetlabs.puppetdb.http.v3.node
  (:require [com.puppetlabs.puppetdb.http.v2.node :as v2-node]
            [com.puppetlabs.puppetdb.http.paging :as paging])
  (:use [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params wrap-with-paging-options)]))

(def node-app
  (-> v2-node/routes
    (verify-accepts-json)
    (validate-query-params
      {:optional (cons "query" paging/query-params)})
    (wrap-with-paging-options)))
