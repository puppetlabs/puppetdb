(ns com.puppetlabs.puppetdb.http.v3.facts
  (:require [com.puppetlabs.puppetdb.http.v2.facts :as v2-facts]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                  (v2-facts/query-facts (params "query") paging-options (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(def facts-app
  (v2-facts/build-facts-app
    (-> query-app
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options)))
