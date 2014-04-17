(ns com.puppetlabs.puppetdb.http.resources
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resources :as r]
            [ring.util.response :as rr]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.http :refer [remove-environment remove-all-environments]]
            [com.puppetlabs.puppetdb.query :as query])
  (:use [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only (verify-accepts-json validate-query-params wrap-with-paging-options)]
        [com.puppetlabs.jdbc :only (with-transacted-connection get-result-count)]
        [com.puppetlabs.puppetdb.http :only (add-headers)]))

(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the v2 API specification"
  [version]
  (fn [rows]
    (map #(clojure.set/rename-keys % {:file :sourcefile :line :sourceline}) rows)))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (let [parsed-query (json/parse-string query true)
            {[sql & params] :results-query
             count-query    :count-query} (r/query->sql version parsed-query paging-options)
            row-munge (case version
                        :v1 (throw (IllegalArgumentException. "api v1 is retired"))
                        :v2 (comp r/deserialize-params (munge-result-rows :v2))
                        r/deserialize-params)
            resp (pl-http/stream-json-results
                  (fn [f]
                    ((query/streamed-query-result db version sql params) (comp f row-munge))))]

        (if count-query
          (add-headers resp {:count (get-result-count count-query)})
          resp)))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(defn query-app
  [version]
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options]}]
                  (produce-body
                    version
                    (params "query")
                    paging-options
                    (:scf-read-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(defn build-resources-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [type title &]
    (comp query-app
          (partial http-q/restrict-resource-query-to-type type)
          (partial http-q/restrict-resource-query-to-title title))

    [type &]
    (comp query-app (partial http-q/restrict-resource-query-to-type type))))

(defn resources-app
  [version]
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (build-resources-app
          (validate-query-params (query-app version) {:optional ["query"]}))
    (build-resources-app
      (-> (query-app version)
        (validate-query-params
          {:optional (cons "query" paging/query-params)})
        (wrap-with-paging-options)))))
