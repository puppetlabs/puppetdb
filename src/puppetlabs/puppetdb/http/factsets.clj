(ns puppetlabs.puppetdb.http.factsets
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]))

(defn build-factsets-app
  [globals entity]
  (let [{:keys [api-version url-prefix scf-read-db]} globals]
    (fn [{:keys [params paging-options]}]
      (produce-streaming-body
       entity
       api-version
       (params "query")
       paging-options
       scf-read-db
       url-prefix))))

(defn routes
  [globals]
  (app
   []
   {:get (comp (build-factsets-app globals :factsets)
               http-q/restrict-query-to-active-nodes)}

   [node "facts" &]
   (comp (facts/facts-app globals false) (partial http-q/restrict-query-to-node node))))

(defn factset-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
