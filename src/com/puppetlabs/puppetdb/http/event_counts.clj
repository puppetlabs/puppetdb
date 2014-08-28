(ns com.puppetlabs.puppetdb.http.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as event-counts]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [com.puppetlabs.puppetdb.http.events :as events-http]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.puppetdb.query :as query]))

(defn routes
  [version]
  (app
    [""]
    {:get (fn [{:keys [params globals paging-options]}]
            (let [{:strs [query summarize-by counts-filter count-by] :as query-params} params
                  query-options (merge {:counts-filter (if counts-filter (json/parse-strict-string counts-filter true))
                                        :count-by count-by}
                                       (events-http/validate-distinct-options! query-params))]
              (produce-streaming-body
                :event-counts
                version
                query
                [summarize-by query-options paging-options]
                (:scf-read-db globals))))}))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional (concat ["counts-filter" "count-by"
                                                 "distinct-resources" "distinct-start-time"
                                                 "distinct-end-time"]
                                          paging/query-params) })
      wrap-with-paging-options))
