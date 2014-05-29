(ns com.puppetlabs.puppetdb.http.resources
  (:require [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resources :as r]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query-eng :as qe]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]
            [com.puppetlabs.puppetdb.http :refer [add-headers]]))

(defn produce-body
  "Given a query, and database connection, return a Ring response with the query
  results.

  If the query can't be parsed, a 400 is returned."
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query    :count-query} (case version
                                            (:v2 :v3)
                                            (r/query->sql version parsed-query paging-options)

                                            (qe/compile-user-query->sql
                                             qe/resources-query parsed-query paging-options))
            resp (pl-http/stream-json-response
                  (fn [f]
                    (with-transacted-connection db
                      (query/streamed-query-result version sql params
                                                   (comp f (r/munge-result-rows version))))))]

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
