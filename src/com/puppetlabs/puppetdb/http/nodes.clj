(ns com.puppetlabs.puppetdb.http.nodes
  (:require [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.nodes :as node]
            [com.puppetlabs.puppetdb.http.facts :as f]
            [com.puppetlabs.puppetdb.http.resources :as r]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http])
  (:use [net.cgrand.moustache :only (app)]
        [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params wrap-with-paging-options)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on
  `query`. If no `query` is supplied, all nodes will be returned."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (let [query (if query (json/parse-string query true))
            sql   (node/query->sql version query)
            nodes (node/query-nodes sql paging-options)]
        (query-result-response nodes)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(defn node-status
  "Produce a response body for a request to obtain status information
  for the given node."
  [version node db]
  (if-let [status (with-transacted-connection db
                    (node/status version node))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " node)} pl-http/status-not-found)))

(defn routes
  [version]
  (app
    []
    {:get (comp
            (fn [{:keys [params globals paging-options]}]
              (search-nodes version (params "query") paging-options (:scf-read-db globals)))
            http-q/restrict-query-to-active-nodes)}

    [node]
    {:get (fn [{:keys [globals]}]
            (node-status version node (:scf-read-db globals)))}

    [node "facts" &]
    (comp (f/facts-app version) (partial http-q/restrict-query-to-node node))

    [node "resources" &]
    (comp (r/resources-app version) (partial http-q/restrict-query-to-node node))))

(defn node-app
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "No support for v1 api"))
    :v2 (-> (routes version)
          verify-accepts-json
          (validate-query-params {:optional ["query"]}))
    (-> (routes version)
      (verify-accepts-json)
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      (wrap-with-paging-options))))
