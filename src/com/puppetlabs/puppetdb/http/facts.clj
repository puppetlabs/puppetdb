(ns com.puppetlabs.puppetdb.http.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]
            [com.puppetlabs.puppetdb.http :refer [remove-environment remove-all-environments]])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection get-result-count)]
        [com.puppetlabs.puppetdb.http :only (query-result-response add-headers)]))

(defn query-facts
  "Accepts a `query` and a `db` connection, and returns facts matching the
  query. If the query can't be parsed or is invalid, a 400 error will be
  returned, and a 500 if something else goes wrong."
  [version query paging-options db]
  (try
    (case version
      :v1 (throw (IllegalArgumentException. "api v1 is retired"))
      :v2 nil
      (paging/validate-order-by! [:certname :name :value] paging-options))
    (with-transacted-connection db
      (let [parsed-query (json/parse-string query true)
            {[sql & params] :results-query
             count-query :count-query} (f/query->sql version parsed-query paging-options)

             resp (pl-http/json-response*
                   (pl-http/streamed-response buffer
                      (with-transacted-connection db
                        (f/with-queried-facts sql paging-options params
                          #(pl-http/stream-json (remove-all-environments version %) buffer)))))]

        (if count-query
          (add-headers resp {:count (get-result-count count-query)})
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
                  (query-facts version (params "query") paging-options (:scf-read-db globals)))
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
