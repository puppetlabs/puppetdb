(ns puppetlabs.puppetdb.http.events
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :as middleware]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        param-spec {:optional (concat
                                ["query"
                                 "distinct_resources"
                                 "distinct_start_time"
                                 "distinct_end_time"]
                                paging/query-params)}
        query-route #(apply (partial http-q/query-route :events version param-spec) %)]
    (app
      []
      (query-route handlers))))

(defn events-app
  "Ring app for querying events"
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      middleware/verify-accepts-json
      middleware/wrap-with-paging-options))
