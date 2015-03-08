(ns puppetlabs.puppetdb.http.edges
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]))


(defn build-edges-app
  [version entity]
  (fn [{:keys [params globals paging-options]}]
              (produce-streaming-body
                entity
                version
                (params "query")
                paging-options
                (:scf-read-db globals))))

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
