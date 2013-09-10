(ns com.puppetlabs.puppetdb.http.v2.facts
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.v3.facts :as v3-facts])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware))

(def query-app
  (app
   [&]
   {:get (comp (fn [{:keys [params globals] :as request}]
                 (v3-facts/query-facts (params "query") {} (:scf-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(def facts-app
  (v3-facts/build-facts-app
    (validate-query-params query-app {:optional ["query"]})))
