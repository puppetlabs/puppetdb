(ns com.puppetlabs.puppetdb.http.environments
  (:require [com.puppetlabs.puppetdb.query.environments :as e]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options]]
            [com.puppetlabs.puppetdb.http.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.http.resources :as r]
            [com.puppetlabs.puppetdb.http.events :as ev]
            [com.puppetlabs.puppetdb.http.reports :as rp]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]
            [com.puppetlabs.cheshire :as json]))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query    :count-query} (e/query->sql version parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (with-transacted-connection db
                      (query/streamed-query-result version sql params f))))]

        (if count-query
          (http/add-headers resp {:count (get-result-count count-query)})
          resp)))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(defn environment-status
  "Produce a response body for a single environment."
  [version environment db]
  (if-let [status (with-transacted-connection db
                    (e/status version environment))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " environment)} pl-http/status-not-found)))

(defn routes
  [version]
  (app
   []
   {:get
    (-> (fn [{:keys [params globals paging-options]}]
          (produce-body
           version
           (params "query")
           paging-options
           (:scf-read-db globals)))
        (validate-query-params
         {:optional (cons "query" paging/query-params)}))}

   [environment]
   {:get
    (-> (fn [{:keys [globals]}]
          (environment-status version environment (:scf-read-db globals)))
        ;; Being a singular item, querying and pagination don't really make
        ;; sense here
        (validate-query-params {}))}

   [environment "facts" &]
   {:get
    (comp (f/facts-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "resources" &]
   {:get
    (comp (r/resources-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "events" &]
   {:get
    (comp (ev/events-app version) (partial http-q/restrict-query-to-environment environment))}

   [environment "reports" &]
   {:get
    (comp (rp/reports-app version) (partial http-q/restrict-query-to-environment environment))}))

(defn environments-app
  [version]
  (case version
    (:v2 :v3) (throw (IllegalArgumentException. (str "Environment queries not supported on " (name version))))
    (-> (routes version)
        (verify-accepts-json)
        (wrap-with-paging-options))))
