(ns com.puppetlabs.puppetdb.http.facts
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.facts :as facts]
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
             count-query :count-query} (facts/query->sql version parsed-query
                                                         paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (jdbc/with-transacted-connection db
                      (query/streamed-query-result version sql params
                                                   (comp f (facts/munge-result-rows version))))))]
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

(defn build-facts-app
  [query-app]
  (app
   []
   (verify-accepts-json query-app)

   [fact value &]
   (comp query-app
         (partial http-q/restrict-fact-query-to-name fact)
         (partial http-q/restrict-fact-query-to-value value))

   [fact &]
   (comp query-app
         (partial http-q/restrict-fact-query-to-name fact))))

(defn facts-app
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "No support for v1 for facts end-point"))
    :v2 (build-facts-app
         (-> (query-app version)
             (validate-query-params
              {:optional ["query"]})))
    (build-facts-app
     (-> (query-app version)
         (validate-query-params
          {:optional (cons "query" paging/query-params)})
         wrap-with-paging-options))))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (streamed-response (quote defun)))
;; End:
