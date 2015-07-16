(ns com.puppetlabs.puppetdb.testutils.event-counts
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.fixtures :refer :all]
            [com.puppetlabs.puppetdb.testutils :refer [get-request assert-success!]]))

(defn- json-encode-counts-filter
  "Given the `params` to an event-counts query, convert the counts-filter
  parameter to a JSON string if it is present, otherwise do nothing."
  [params]
  (if-let [counts-filter (params "counts-filter")]
    (assoc params "counts-filter" (json/generate-string counts-filter))
    params))

(defn get-response
  "Utility function to query either the `event-counts` or `aggregate-event-counts`
  endpoint and return the results for use in test comparisons."
  ([endpoint query summarize-by]
   (get-response endpoint query summarize-by {}))
  ([endpoint query summarize-by extra-query-params]
   (get-response endpoint query summarize-by extra-query-params false))
  ([endpoint query summarize-by extra-query-params ignore-failure?]
   (let [response (*app* (get-request endpoint
                                      query
                                      (-> extra-query-params
                                          (assoc "summarize-by" summarize-by)
                                          (json-encode-counts-filter))))]
     (when-not ignore-failure?
       (assert-success! response))
     (if (string? (:body response))
       response
       (update-in response [:body] slurp)))))
