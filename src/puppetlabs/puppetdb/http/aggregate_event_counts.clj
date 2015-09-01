(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        param-spec {:required ["summarize_by"]
                    :optional ["query" "counts_filter" "count_by"
                               "distinct_resources" "distinct_start_time"
                               "distinct_end_time"]}
        query-route #(apply (partial http-q/query-route :aggregate-event-counts
                                     version param-spec) %)]
    (app
      []
      (query-route handlers))))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json))
