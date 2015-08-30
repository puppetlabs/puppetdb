(ns puppetlabs.puppetdb.http.event-counts
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        query-route #(apply (partial http-q/query-route :event-counts version) %)]
    (app
      []
      (query-route handlers))))

(defn event-counts-app
  "Ring app for querying for summary information about resource events."
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      verify-accepts-json
      (validate-query-params {:optional (concat ["query"
                                                 "summarize_by"
                                                 "counts_filter" "count_by"
                                                 "distinct_resources" "distinct_start_time"
                                                 "distinct_end_time"]
                                                paging/query-params)})
      wrap-with-paging-options))
