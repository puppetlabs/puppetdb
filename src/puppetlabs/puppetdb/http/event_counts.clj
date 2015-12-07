(ns puppetlabs.puppetdb.http.event-counts
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [param-spec {:required ["summarize_by"]
                    :optional (concat ["counts_filter" "count_by"
                                       "distinct_resources" "distinct_start_time"
                                       "distinct_end_time"]
                                      paging/query-params)}]
    (app
      []
      (http-q/query-route-from "event_counts" version param-spec optional-handlers))))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json
      wrap-with-paging-options))
