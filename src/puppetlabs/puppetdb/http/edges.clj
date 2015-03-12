(ns puppetlabs.puppetdb.http.edges
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn build-edges-app
  [version entity]
  (comp
   (fn [{:keys [params globals paging-options]}]
     (produce-streaming-body
      entity
      version
      (params "query")
      paging-options
      (:scf-read-db globals)
      (:url-prefix globals)))
   http-q/restrict-query-to-active-nodes))

(defn routes
  [version]
  (app
    [""]
    {:get (build-edges-app version :edges)}))

(defn edges-app
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
