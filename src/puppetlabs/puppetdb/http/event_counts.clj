(ns puppetlabs.puppetdb.http.event-counts
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :as events-http]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (let [{:strs [query summarize_by counts_filter count_by] :as query-params} params
                 query-options (merge {:counts_filter (if counts_filter (json/parse-strict-string counts_filter true))
                                       :count_by count_by}
                                      (events-http/validate-distinct-options! query-params))]
             (produce-streaming-body
              :event-counts
              version
              query
              [summarize_by query-options paging-options]
              (:scf-read-db globals))))}))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize_by"]
                              :optional (concat ["counts_filter" "count_by"
                                                 "distinct_resources" "distinct_start_time"
                                                 "distinct_end_time"]
                                                paging/query-params) })
      wrap-with-paging-options))
