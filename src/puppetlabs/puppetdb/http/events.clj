(ns puppetlabs.puppetdb.http.events
  (:import (java.sql Timestamp))
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.puppetdb.middleware :as middleware]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]))

(defn validate-distinct-options!
  "Validate the HTTP query params related to a `distinct_resources` query.  Return a
  map containing the validated `distinct_resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params]
  {:pre [(map? params)]
   :post [(map? %)
          (every? (partial contains? %) #{:distinct_resources? :distinct_start_time :distinct_end_time})
          (kitchensink/boolean? (:distinct_resources? %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct_start_time %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct_end_time %))]}
  (let [distinct-params ["distinct_resources" "distinct_start_time" "distinct_end_time"]]
    (cond
     (not-any? #(contains? params %) distinct-params)
     {:distinct_resources? false
      :distinct_start_time nil
      :distinct_end_time   nil}

     (every? #(contains? params %) distinct-params)
     (let [start (to-timestamp (params "distinct_start_time"))
           end   (to-timestamp (params "distinct_end_time"))]
       (when (some nil? [start end])
         (throw (IllegalArgumentException.
                 (str "query parameters 'distinct_start_time' and 'distinct_end_time' must be valid datetime strings: "
                      (params "distinct_start_time") " "
                      (params "distinct_end_time")))))
       {:distinct_resources? (http/parse-boolean-query-param params "distinct_resources")
        :distinct_start_time start
        :distinct_end_time   end})

     :else
     (throw (IllegalArgumentException.
             "'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'")))))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (try
             (let [query-options (validate-distinct-options! params)]
               (produce-streaming-body
                :events
                version
                (params "query")
                [query-options paging-options]
                (:scf-read-db globals)))
             (catch IllegalArgumentException e
               (http/error-response e))))}))

(defn events-app
  "Ring app for querying events"
  [version]
  (-> (routes version)
      middleware/verify-accepts-json
      (middleware/validate-query-params {:required ["query"]
                                         :optional (concat
                                                    ["distinct_resources"
                                                     "distinct_start_time"
                                                     "distinct_end_time"]
                                                    paging/query-params)})
      middleware/wrap-with-paging-options))
