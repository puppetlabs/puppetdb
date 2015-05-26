(ns puppetlabs.puppetdb.http.factsets
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]))

(defn build-factsets-app
  [version entity]
  (fn [{:keys [params globals paging-options]}]
    (produce-streaming-body
     entity
     version
     (params "query")
     paging-options
     (:scf-read-db globals)
     (:url-prefix globals))))

(defn routes
  [version]
  (app
   []
   {:get (comp (build-factsets-app version :factsets)
               http-q/restrict-query-to-active-nodes)}

   [node "facts" &]
   (-> (comp (facts/facts-app version false) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check version :factset node))))

(defn factset-app
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
