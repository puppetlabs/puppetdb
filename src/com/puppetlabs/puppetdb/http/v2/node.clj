(ns com.puppetlabs.puppetdb.http.v2.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.query.node :as node]
            [com.puppetlabs.puppetdb.http.v2.facts :as f]
            [com.puppetlabs.puppetdb.http.v2.resources :as r]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http])
  (:use [net.cgrand.moustache :only (app)]
        [com.puppetlabs.middleware :only (verify-accepts-json verify-no-paging-params)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on
  `query`. If no `query` is supplied, all nodes will be returned."
  [query paging-options db]
  (try
    (with-transacted-connection db
      (let [query (if query (json/parse-string query true))
            sql   (node/v2-query->sql query)
            nodes (node/query-nodes sql paging-options)]
        (pl-http/json-response nodes)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(defn node-status
  "Produce a response body for a request to obtain status information
  for the given node."
  [node db]
  (if-let [status (with-transacted-connection db
                    (node/status node))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " node)} pl-http/status-not-found)))

(def routes
  (app
    []
    {:get (comp
            (fn [{:keys [params globals paging-options]}]
              (search-nodes (params "query") paging-options (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}

    [node]
    {:get (fn [{:keys [globals]}]
              (node-status node (:scf-db globals)))}

    [node "facts" &]
    (comp f/facts-app (partial http-q/restrict-query-to-node node))

    [node "resources" &]
    (comp r/resources-app (partial http-q/restrict-query-to-node node))))

(def node-app
  (-> routes
      verify-accepts-json
      verify-no-paging-params))
