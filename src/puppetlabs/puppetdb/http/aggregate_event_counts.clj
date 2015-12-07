(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [param-spec {:required ["summarize_by"]
                    :optional ["query" "counts_filter" "count_by"
                               "distinct_resources" "distinct_start_time"
                               "distinct_end_time"]}]
    (app
      []
      (http-q/query-route-from "aggregate_event_counts" version param-spec optional-handlers))))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json))
