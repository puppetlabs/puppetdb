(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :as events-http]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals]}]
           (let [{:strs [query summarize_by counts_filter count_by] :as query-params} params
                 distinct-options (events-http/validate-distinct-options! query-params)
                 query-options (-> {:counts_filter (when counts_filter (json/parse-string counts_filter true))
                                    :count_by count_by}
                                   (merge distinct-options))]
             (produce-streaming-body
              :aggregate-event-counts
              version
              query
              [summarize_by query-options]
              (:scf-read-db globals)
              (:url-prefix globals))))}))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize_by"]
                              :optional ["counts_filter" "count_by" "distinct_resources"
                                         "distinct_start_time" "distinct_end_time"]})))
