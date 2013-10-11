(ns com.puppetlabs.puppetdb.http.v2.resources
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resources :as r]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params)]
        [com.puppetlabs.jdbc :only (with-transacted-connection get-result-count)]
        [com.puppetlabs.puppetdb.http :only (query-result-response add-headers)]))


(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the v2 API specification"
  [rows]
  (map #(clojure.set/rename-keys % {:file :sourcefile :line :sourceline}) rows))

(defn produce-body
  "Given a a query, and database connection, return a Ring response
  with the query results.

  If the query can't be parsed, a 400 is returned."
  [query paging-options db]
  (try
    (let [{[sql & params] :results-query
           count-query   :count-query} (with-transacted-connection db
                                         (-> query
                                           (json/parse-string true)
                                             (r/v2-query->sql paging-options)))
          result       (pl-http/streamed-response buffer
                         (with-transacted-connection db
                           (r/with-queried-resources sql params
                             #(pl-http/stream-json (munge-result-rows %) buffer))))
          query-result (if count-query
                         {:result result :count (get-result-count count-query)}
                         {:result result})]
      (-> (:result query-result)
          rr/response
          (add-headers (dissoc query-result :result))
          (rr/header "Content-Type" "application/json")
          (rr/charset "utf-8")
          (rr/status pl-http/status-ok)))

    (catch IllegalArgumentException e
      ;; Query compilation error
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals]}]
                  (produce-body
                    (params "query")
                    {}
                    (:scf-read-db globals)))
                http-q/restrict-query-to-active-nodes)}))

(defn build-resources-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [type title &]
    (comp query-app (partial http-q/restrict-resource-query-to-type type) (partial http-q/restrict-resource-query-to-title title))

    [type &]
    (comp query-app (partial http-q/restrict-resource-query-to-type type))))

(def resources-app
  (build-resources-app
    (validate-query-params query-app {:optional ["query"]})))


