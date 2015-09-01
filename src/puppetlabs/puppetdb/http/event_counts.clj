(ns puppetlabs.puppetdb.http.event-counts
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        param-spec {:required ["summarize_by"]
                    :optional (concat ["query"
                                       "counts_filter" "count_by"
                                       "distinct_resources" "distinct_start_time"
                                       "distinct_end_time"]
                                      paging/query-params)}
        query-route #(apply (partial http-q/query-route :event-counts version param-spec) %)]
    (app
      []
      (query-route handlers))))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json
      wrap-with-paging-options))
