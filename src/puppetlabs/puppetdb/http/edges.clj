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
  ([globals entity] (build-edges-app globals entity true))
  ([globals entity restrict-to-active-nodes]
   (comp
    (fn [{:keys [params paging-options]}]
      (produce-streaming-body
       entity
       (:api-version globals)
       (params "query")
       paging-options
       (:scf-read-db globals)
       (:url-prefix globals)))
    (if restrict-to-active-nodes
      http-q/restrict-query-to-active-nodes
      identity))))

(defn routes
  ([globals] (routes globals true))
  ([globals restrict-to-active-nodes]
   (app
    [""]
    {:get (build-edges-app globals :edges restrict-to-active-nodes)})))

(defn edges-app
  ([globals] (edges-app globals true))
  ([globals restrict-to-active-nodes]
   (-> (routes globals restrict-to-active-nodes)
       verify-accepts-json
       (validate-query-params
        {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
