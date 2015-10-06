(ns puppetlabs.puppetdb.testutils.event-counts
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request assert-success!]]))

(defn- json-encode-counts-filter
  "Given the `params` to an event-counts query, convert the counts-filter
  parameter to a JSON string if it is present, otherwise do nothing."
  [params]
  (if-let [counts-filter (params "counts_filter")]
    (assoc params "counts_filter" (json/generate-string counts-filter))
    params))
