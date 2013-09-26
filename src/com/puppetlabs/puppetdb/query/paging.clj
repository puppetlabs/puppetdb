;; ## Paging query parameter manipulation
;;
;; Functions that aid in the validation and processing of the
;; query parameters related to paging PuppetDB queries

(ns com.puppetlabs.puppetdb.query.paging
  (:import  [com.fasterxml.jackson.core JsonParseException])
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:use     [com.puppetlabs.utils :only [keyset seq-contains?]]
            [com.puppetlabs.jdbc :only [underscores->dashes]]
            [com.puppetlabs.http :only [parse-boolean-query-param]]
            [clojure.walk :only (keywordize-keys)]))

(def query-params ["limit" "offset" "order-by" "include-total"])
(def count-header "X-Records")

(defn parse-order-by-json
  "Parses a JSON order-by string.  Returns the parsed string, or a Ring
  error response with a useful error message if there was a parse failure."
  [order-by]
  (try
    (json/parse-string order-by true)
    (catch JsonParseException e
      (throw (IllegalArgumentException.
        (str "Illegal value '" order-by "' for :order-by; expected "
          "an array of maps."))))))

(defn validate-order-by-data-structure
  "Validates an order-by data structure.  The value must be `nil`, an empty list,
  or a list of maps.  Returns the input if validation is successful, or a
  Ring error response with a useful error message if the validation fails."
  [order-by]
  (if (or (empty? order-by)
          ((every-pred sequential? #(every? map? %)) order-by))
    order-by
    (throw (IllegalArgumentException.
      (str "Illegal value '" order-by "' for :order-by; expected "
        "an array of maps.")))))

(defn validate-required-order-by-fields
  "Validates that each map in the order-by list contains the required
  key ':field'.  Returns the input if validation is successful, or a
  Ring error response with a useful error message if validation fails."
  [order-by]
  (if-let [bad-order-by (some
                          (fn [x] (when-not (contains? x :field) x))
                          order-by)]
    (throw (IllegalArgumentException.
      (str "Illegal value '" bad-order-by "' in :order-by; "
         "missing required key 'field'.")))
    order-by))

(defn validate-no-invalid-order-by-fields
  "Validates that each map in the order-by list does not contain any invalid
  keys.  Legal keys are ':field' and ':order'.  Returns the input if validation
  was successful, or a Ring error response with a useful error message if"
  [order-by]
  (if-let [bad-order-by (some
                          (fn [x] (when (keys (dissoc x :field :order)) x))
                          order-by)]
    (throw (IllegalArgumentException.
             (str "Illegal value '" bad-order-by "' in :order-by; "
              "unknown key '" (first (keys (dissoc bad-order-by :field :order))) "'.")))
    order-by))

(defn parse-order-by
  "Given a map of paging-options, validates that the order-by field conforms to
  our specifications:

  * Must be a JSON string
  * Must deserialize to an array of maps
  * Each map represents a field to order by in the final query; must contain
    a key 'field'
  * Each map may also contain the optional key 'order', but may not contain
    any other keys

  Assuming that these conditions are met, the function will return the paging-options
  map with an updated/sanitized version of the 'order-by' value; deserialized
  from JSON into a map, keywordized-keys, etc.

  If validation fails, this function will return a Ring error response with
  an informative error message as to the cause of the failure."
  [paging-options]
  {:post [(map? %)
          (= (keyset %) (keyset paging-options))]}
  (if-let [order-by (paging-options :order-by)]
    (->> order-by
      (parse-order-by-json)
      (validate-order-by-data-structure)
      (validate-required-order-by-fields)
      (validate-no-invalid-order-by-fields)
      (assoc paging-options :order-by))
    paging-options))

(defn parse-count
  "Parse the optional `include-total` query parameter in the paging options map,
  and return an updated map with the correct boolean value."
  [paging-options]
  (let [count? (parse-boolean-query-param paging-options :include-total)]
    (-> paging-options
      (dissoc :include-total)
      (assoc :count? count?))))

(defn validate-order-by!
  "Given a list of keywords representing legal fields for ordering a query, and a map of
   paging options, validate that the order-by data in the paging options complies with
   the list of fields.  Throws an exception if validation fails."
  [columns paging-options]
  {:pre [(sequential? columns)
         (every? (some-fn string? keyword?) columns)
         ((some-fn nil? map?) paging-options)]}
  (let [columns (map underscores->dashes columns)]
    (doseq [field (map :field (:order-by paging-options))]
      (when-not (seq-contains? columns field)
        (throw (IllegalArgumentException.
          (format "Unrecognized column '%s' specified in :order-by; Supported columns are '%s'"
                  field
                  (string/join "', '" columns))))))))

(defn requires-paging?
  "Given a paging-options map, return true if the query requires paging
  and false if it does not."
  [{:keys [limit offset order-by count?] :as paging-options}]
  (not
    (and
      (every? nil? [limit offset])
      ((some-fn nil? (every-pred coll? empty?)) order-by)
      (not count?))))
