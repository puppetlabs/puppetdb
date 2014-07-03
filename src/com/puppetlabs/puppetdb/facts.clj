(ns com.puppetlabs.puppetdb.facts
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.cheshire :as json]))

(defn flatten-fact-value
  "Flatten a fact value to a string either using JSON or coercement depending on
  the type."
  [value]
  {:post [(string? %)]}
  (cond
   (string? value) value
   (kitchensink/boolean? value) (str value)
   (integer? value) (str value)
   (float? value) (str value)
   (map? value) (json/generate-string value)
   (coll? value) (json/generate-string value)
   :else (throw (IllegalArgumentException. (str "Value " value " is not valid for flattening")))))

(defn flatten-fact-value-map
  "Flatten a map of facts depending on the type of the value."
  [factmap]
  (reduce-kv (fn [acc k v]
               (assoc acc k (flatten-fact-value v)))
             {} factmap))
