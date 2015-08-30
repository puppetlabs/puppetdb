(ns puppetlabs.puppetdb.http.events
  (:import (java.sql Timestamp))
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body']]
            [puppetlabs.puppetdb.middleware :as middleware]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [schema.core :as s]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]))

(defn-validated validate-distinct-options! :- {:distinct_resources? s/Bool
                                               :distinct_start_time (s/maybe pls/Timestamp)
                                               :distinct_end_time (s/maybe pls/Timestamp)}
  "Validate the HTTP query params related to a `distinct_resources` query.  Return a
  map containing the validated `distinct_resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params :- {s/Any s/Any}]
  (let [distinct-params-names #{"distinct_resources" "distinct_start_time" "distinct_end_time"}
        {:strs [distinct_start_time distinct_end_time] :as distinct-params}
        (select-keys params distinct-params-names)]
    (condp = (kitchensink/keyset distinct-params)
     #{}
     {:distinct_resources? false
      :distinct_start_time nil
      :distinct_end_time   nil}

     distinct-params-names
     (let [start (to-timestamp distinct_start_time)
           end   (to-timestamp distinct_end_time)]
       (when (some nil? [start end])
         (throw (IllegalArgumentException.
                 (str "query parameters 'distinct_start_time' and 'distinct_end_time' must be valid datetime strings: "
                      distinct_start_time " " distinct_end_time))))
       {:distinct_resources? (http/parse-boolean-query-param distinct-params "distinct_resources")
        :distinct_start_time start
        :distinct_end_time   end})

     #{"distinct_start_time" "distinct_end_time"}
     (throw (IllegalArgumentException.
             "'distinct_resources' query parameter must accompany parameters 'distinct_start_time' and 'distinct_end_time'"))
     (throw (IllegalArgumentException.
             "'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'")))))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        query-route #(apply (partial http-q/query-route :events version) %)]
    (app
      []
      (query-route handlers))))

(defn events-app
  "Ring app for querying events"
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      middleware/verify-accepts-json
      (middleware/validate-query-params {:optional (concat
                                                    ["query"
                                                     "distinct_resources"
                                                     "distinct_start_time"
                                                     "distinct_end_time"]
                                                    paging/query-params)})
      middleware/wrap-with-paging-options))
