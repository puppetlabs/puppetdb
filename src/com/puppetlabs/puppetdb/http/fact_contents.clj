(ns com.puppetlabs.puppetdb.http.fact-contents
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.fact-contents :as fact-contents]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :as http]))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (jdbc/with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query :count-query} (fact-contents/query->sql version parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (jdbc/with-transacted-connection db
                      (query/streamed-query-result
                       version sql params (comp f fact-contents/munge-result-rows)))))]
        (if count-query
          (http/add-headers resp {:count (jdbc/get-result-count count-query)})
          resp)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(defn query-app
  [version]
  (app
   [&]
   {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                 (produce-body
                  version
                  (params "query")
                  paging-options
                  (:scf-read-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(defn routes
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn fact-contents-app
  [version]
  (routes
   (-> (query-app version)
       (validate-query-params
        {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
