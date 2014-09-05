(ns puppetlabs.puppetdb.http.event-counts
  (:require [puppetlabs.puppetdb.query.event-counts :as event-counts]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :as events-http]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query :as query]))

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
