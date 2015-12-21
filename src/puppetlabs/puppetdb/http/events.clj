(ns puppetlabs.puppetdb.http.events
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.http.query :as http-q]))

(defn events-app
  "Ring app for querying events"
  [version & optional-handlers]
  (let [param-spec {:optional (concat
                               ["query"
                                "distinct_resources"
                                "distinct_start_time"
                                "distinct_end_time"]
                               paging/query-params)}]
    {"" (http-q/query-route-from' "events" version param-spec optional-handlers)}))
