(ns puppetlabs.puppetdb.http.nodes
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query.nodes :as node]
            [puppetlabs.puppetdb.http.facts :as f]
            [puppetlabs.puppetdb.http.resources :as r]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query-eng.engine :as qe]))

(defn node-status
  "Produce a response body for a request to obtain status information
  for the given node."
  [version node db]
  (if-let [status (jdbc/with-transacted-connection db
                    (node/status version node))]
    (http/json-response status)
    (http/json-response {:error (str "No information is known about " node)} http/status-not-found)))

(defn routes
  [version]
  (app
   []
   {:get (comp
          (fn [{:keys [params globals paging-options]}]
            (produce-streaming-body
             :nodes
             version
             (params "query")
             paging-options
             (:scf-read-db globals)))
          http-q/restrict-query-to-active-nodes)}

   [node]
   {:get
    (-> (fn [{:keys [globals]}]
          (node-status version node (:scf-read-db globals)))
        ;; Being a singular item, querying and pagination don't really make
        ;; sense here
        (validate-query-params {}))}

   [node "facts" &]
   (comp (f/facts-app version) (partial http-q/restrict-query-to-node node))

   [node "resources" &]
   (comp (r/resources-app version) (partial http-q/restrict-query-to-node node))))

(defn node-app
  [version]
  (-> (routes version)
    verify-accepts-json
    (validate-query-params
     {:optional (cons "query" paging/query-params)})
    wrap-with-paging-options))
