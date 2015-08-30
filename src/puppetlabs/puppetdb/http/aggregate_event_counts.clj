(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        query-route #(apply (partial http-q/query-route :aggregate-event-counts version) %)]
    (app
      []
      (query-route handlers))))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json
      (validate-query-params {:optional ["summarize_by"
                                         "query"
                                         "counts_filter" "count_by" "distinct_resources"
                                         "distinct_start_time" "distinct_end_time"]})))
