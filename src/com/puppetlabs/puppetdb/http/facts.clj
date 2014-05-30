(ns com.puppetlabs.puppetdb.http.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.http :as pl-http]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]
            [com.puppetlabs.puppetdb.http :refer [add-headers]]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(defn produce-body
  "Accepts a `query` and a `db` connection, and returns facts matching the
  query. If the query can't be parsed or is invalid, a 400 error will be
  returned, and a 500 if something else goes wrong."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (case version
        :v1 (throw (IllegalArgumentException. "api v1 is retired"))
        :v2 nil
        (paging/validate-order-by! [:certname :name :value :environment] paging-options))
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query :count-query}  (if (= :v4 version)
                                          (qe/compile-user-query->sql qe/facts-query parsed-query paging-options)
                                          (f/query->sql version parsed-query paging-options))
            resp (pl-http/stream-json-response
                  (fn [f]
                    (with-transacted-connection db
                      (query/streamed-query-result version sql params f))))]

        (if count-query
          (add-headers resp {:count (with-transacted-connection db
                                      (get-result-count count-query))})
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
    (comp query-app (partial http-q/restrict-fact-query-to-name fact))))

(defn facts-app
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "No support for v1 for facts end-point"))
    :v2 (build-facts-app
          (validate-query-params (query-app version) {:optional ["query"]}))
    (build-facts-app
      (-> (query-app version)
        (validate-query-params
          {:optional (cons "query" paging/query-params)})
        wrap-with-paging-options))))


;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (streamed-response (quote defun)))
;; End:
