(ns com.puppetlabs.puppetdb.http.v3.facts
  (:require [com.puppetlabs.puppetdb.http.v2.facts :as v2-facts]
            [com.puppetlabs.puppetdb.http.query :as http-q])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                  (v2-facts/query-facts (params "query") paging-options (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(def facts-app
  (v2-facts/build-facts-app (wrap-with-paging-options query-app)))
