(ns puppetlabs.puppetdb.testutils.facts
  (:require [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.utils :as utils]))

(defn munge-facts
  "Munges facts so they may be compared"
  [facts]
  (->> facts
       utils/vector-maybe
       (map clojure.walk/stringify-keys)
       (map #(update % "producer_timestamp" to-string))))
