(ns com.puppetlabs.puppetdb.http.fact-paths
  (:require [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]
            [com.puppetlabs.jdbc :as jdbc]
            [clojure.string :as str]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json
                                               validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.facts :refer [factpath-delimiter
                                                   string-to-factpath]]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.http :as http]))

(defn paths-to-vecs
  [rows]
  (map #(update-in % [:path] string-to-factpath) rows))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (jdbc/with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query :count-query} (qe/compile-user-query->sql qe/fact-paths-query
                                                                   parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (jdbc/with-transacted-connection db
                      (query/streamed-query-result
                       version sql params (comp f paths-to-vecs)))))]
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
                  (:scf-read-db globals))))}))

(defn routes
  [query-app]
  (app
   []
   (verify-accepts-json query-app)))

(defn fact-paths-app
  [version]
  (routes
   (-> (query-app version)
       verify-accepts-json
       (validate-query-params {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options)))
