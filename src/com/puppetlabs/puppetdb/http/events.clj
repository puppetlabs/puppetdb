(ns com.puppetlabs.puppetdb.http.events
  (:import (java.sql Timestamp))
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :refer [parse-boolean-query-param]]
            [clj-time.coerce :refer [to-timestamp]]))

(defn validate-distinct-options!
  "Validate the HTTP query params related to a `distinct-resources` query.  Return a
  map containing the validated `distinct-resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params]
  {:pre [(map? params)]
   :post [(map? %)
          (every? (partial contains? %) #{:distinct-resources? :distinct-start-time :distinct-end-time})
          (kitchensink/boolean? (:distinct-resources? %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct-start-time %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct-end-time %))]}
  (let [distinct-params ["distinct-resources" "distinct-start-time" "distinct-end-time"]]
    (cond
      (not-any? #(contains? params %) distinct-params)
      {:distinct-resources? false
       :distinct-start-time nil
       :distinct-end-time   nil}

      (every? #(contains? params %) distinct-params)
      (let [start (to-timestamp (params "distinct-start-time"))
            end   (to-timestamp (params "distinct-end-time"))]
        (when (some nil? [start end])
          (throw (IllegalArgumentException.
                   (str "query parameters 'distinct-start-time' and 'distinct-end-time' must be valid datetime strings: "
                        (params "distinct-start-time") " "
                        (params "distinct-end-time")))))
        {:distinct-resources? (parse-boolean-query-param params "distinct-resources")
         :distinct-start-time start
         :distinct-end-time   end})

      :else
      (throw (IllegalArgumentException.
               "'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'")))))
