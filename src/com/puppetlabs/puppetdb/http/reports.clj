(ns com.puppetlabs.puppetdb.http.reports
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.reports :as reports]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query-eng :as qe]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]))

(defn produce-body
  [version query paging-options db]
  (try
    (with-transacted-connection db
      (let [parsed-query (json/parse-strict-string query true)
            {[sql & params] :results-query
             count-query    :count-query} (reports/query->sql version parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (with-transacted-connection db
                      (query/streamed-query-result version sql params
                                                   (comp f (reports/munge-result-rows version))))))]

        (if count-query
          (http/add-headers resp {:count (get-result-count count-query)})
          resp)))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (produce-body
            version
            (params "query")
            paging-options
            (:scf-read-db globals)))}))

(defn reports-app
  "Ring app for querying reports"
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
