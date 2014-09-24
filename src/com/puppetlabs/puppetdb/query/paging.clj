;; ## Paging query parameter manipulation
;;
;; Functions that aid in the validation and processing of the
;; query parameters related to paging PuppetDB queries

(ns com.puppetlabs.puppetdb.query.paging
  (:import  [com.fasterxml.jackson.core JsonParseException])
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.string :as string])
  (:use     [puppetlabs.kitchensink.core :only [keyset seq-contains? parse-int order-by-expr?]]
            [com.puppetlabs.jdbc :only [underscores->dashes]]
            [com.puppetlabs.http :only [parse-boolean-query-param]]
            [clojure.walk :only (keywordize-keys)]))

(def query-params ["limit" "offset" "order-by" "include-total"])
(def count-header "X-Records")

(defn valid-order-str?
  "Predicate that tests whether an 'order' string is valid; legal
  values are nil, 'asc', and 'desc' (case-insensitive)."
  [order]
  (or (nil? order)
      (= "asc" (string/lower-case order))
      (= "desc" (string/lower-case order))))

(defn valid-paging-options?
  "Predicate that tests whether an object represents valid
  paging options, based on the format that is generated
  by the wrap-with-paging-options middleware."
  [{:keys [limit offset order-by] :as paging-options}]
  (and
   (map? paging-options)
   (or
    (nil? limit)
    (pos? limit))
   (or
    (nil? offset)
    (>= offset 0))
   (or
    (nil? order-by)
    (and
     (sequential? order-by)
     (every? order-by-expr? order-by)))))

(defn parse-order-by-json
  "Parses a JSON order-by string.  Returns the parsed string, or a Ring
  error response with a useful error message if there was a parse failure."
  [order-by]
  (try
    ;; If we don't force realization of parse-string right here, then
    ;; we will return a lazy sequence, which upon realization later
    ;; might throw an uncaught JsonParseException.
    (doall (json/parse-string order-by true))
    (catch JsonParseException e
      (throw (IllegalArgumentException.
              (str "Illegal value '" order-by "' for :order-by; expected a JSON "
                   "array of maps."))))))

(defn parse-order-str
  "Given an 'order' string, returns either :ascending or :descending"
  [order]
  {:pre [(valid-order-str? order)]
   :post [(contains? #{:ascending :descending} %)]}
  (if (or (nil? order) (= "asc" (string/lower-case order)))
    :ascending
    :descending))

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

(defn parse-required-order-by-fields
  "Validates that each map in the order-by list contains the required
  key ':field', and a legal value for the optional key ':order' if it
  is provided.  Throws an exception with a useful error message
  if validation fails; otherwise, returns a list of order by expressions
  that satisfy `order-by-expr?`"
  [order-by]
  {:post [(every? order-by-expr? %)]}
  (when-let [bad-order-by (some
                           (fn [x] (when-not (contains? x :field) x))
                           order-by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order-by; "
                 "missing required key 'field'."))))
  (when-let [bad-order-by (some
                           (fn [x] (when-not (valid-order-str? (:order x)) x))
                           order-by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order-by; "
                 "'order' must be either 'asc' or 'desc'"))))
  (map
   (fn [x]
     [(keyword (:field x)) (parse-order-str (:order x))])
   order-by))

(defn validate-no-invalid-order-by-fields
  "Validates that each map in the order-by list does not contain any invalid
  keys.  Legal keys are ':field' and ':order'.  Returns the input if validation
  was successful; throws an exception with a useful error message otherwise."
  [order-by]
  (if-let [bad-order-by (some
                         (fn [x] (when (keys (dissoc x :field :order)) x))
                         order-by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order-by; "
                 "unknown key '" (name (first (keys (dissoc bad-order-by :field :order)))) "'.")))
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

  If validation fails, this function will throw an exception with
  an informative error message as to the cause of the failure."
  [paging-options]
  {:post [(map? %)
          (= (keyset %) (keyset paging-options))
          (every? order-by-expr? (% :order-by))]}
  (if-let [order-by (paging-options :order-by)]
    (->> order-by
         (parse-order-by-json)
         (validate-order-by-data-structure)
         (validate-no-invalid-order-by-fields)
         (parse-required-order-by-fields)
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

(defn validate-limit
  "Validates that the limit string is a positive non-zero integer. Returns the integer
  form if validation was successful, otherwise an IllegalArgumentException is thrown."
  [limit]
  {:pre  [(string? limit)]
   :post [(and (integer? %) (> % 0))]}
  (let [l (parse-int limit)]
    (if ((some-fn nil? neg? zero?) l)
      (throw (IllegalArgumentException.
              (format "Illegal value '%s' for :limit; expected a positive non-zero integer." limit)))
      l)))

(defn parse-limit
  "Parse the optional `limit` query parameter in the paging options map,
  and return an updated map with the correct integer value.
  Throws an exception if the provided limit is not a positive non-zero integer."
  [paging-options]
  (update-in paging-options [:limit] #(when-not (nil? %) (validate-limit %))))

(defn validate-offset
  "Validates that the offset string is a non-negative integer. Returns the integer
  form if validation was successful, otherwise an IllegalArgumentException is thrown."
  [offset]
  {:pre  [(string? offset)]
   :post [(and (integer? %) (>= % 0))]}
  (let [o (parse-int offset)]
    (if ((some-fn nil? neg?) o)
      (throw (IllegalArgumentException.
              (format "Illegal value '%s' for :offset; expected a non-negative integer." offset)))
      o)))

(defn parse-offset
  "Parse the optional `offset` query parameter in the paging options map,
  and return an updated map with the correct integer value.
  Throws an exception if the provided offset is not a non-negative integer."
  [paging-options]
  (update-in paging-options [:offset] #(when-not (nil? %) (validate-offset %))))

(defn validate-order-by!
  "Given a list of keywords representing legal fields for ordering a query, and a map of
   paging options, validate that the order-by data in the paging options complies with
   the list of fields.  Throws an exception if validation fails."
  [columns paging-options]
  {:pre [(sequential? columns)
         (every? keyword? columns)
         ((some-fn nil? valid-paging-options?) paging-options)]}
  (let [columns (map underscores->dashes columns)]
    (doseq [field (map first (:order-by paging-options))]
      (when-not (seq-contains? columns field)
        (throw (IllegalArgumentException.
                (format "Unrecognized column '%s' specified in :order-by; Supported columns are '%s'"
                        (name field)
                        (string/join "', '" (map name columns)))))))))

(defn requires-paging?
  "Given a paging-options map, return true if the query requires paging
  and false if it does not."
  [{:keys [limit offset order-by count?] :as paging-options}]
  (not
   (and
    (every? nil? [limit offset])
    ((some-fn nil? (every-pred coll? empty?)) order-by)
    (not count?))))
