(ns com.puppetlabs.puppetdb.http.nodes
  (:require [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.nodes :as node]
            [com.puppetlabs.puppetdb.http.facts :as f]
            [com.puppetlabs.puppetdb.http.resources :as r]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (jdbc/with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query    :count-query} (node/query->sql version parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (jdbc/with-transacted-connection db
                      (query/streamed-query-result version sql params
                                                   (comp f (node/munge-result-rows version))))))]
        (if count-query
          (http/add-headers resp {:count (jdbc/get-result-count count-query)})
          resp)))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(defn node-status
  "Produce a response body for a request to obtain status information
  for the given node."
  [version node db]
  (if-let [status (jdbc/with-transacted-connection db
                    (node/status version node))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " node)} pl-http/status-not-found)))

(defn routes
  [version]
  (app
   []
   {:get (comp
          (fn [{:keys [params globals paging-options]}]
            (produce-body
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
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (-> (routes version)
            verify-accepts-json
            (validate-query-params {:optional ["query"]}))
    (-> (routes version)
        verify-accepts-json
        (validate-query-params
         {:optional (cons "query" paging/query-params)})
        wrap-with-paging-options)))
