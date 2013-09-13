(ns com.puppetlabs.puppetdb.http.v2.node
  (:require [com.puppetlabs.puppetdb.http.v3.nodes :as v3-node])
  (:use [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params)]))

(def node-app
  (-> v3-node/routes
      verify-accepts-json
      (validate-query-params {:optional ["query"]})))
