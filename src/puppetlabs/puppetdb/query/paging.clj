(ns puppetlabs.puppetdb.query.paging
  "Paging query parameter manipulation

   Functions that aid in the validation and processing of the
   query parameters related to paging PuppetDB queries"
  (:import  [com.fasterxml.jackson.core JsonParseException])
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.string :as string]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.kitchensink.core :as kitchensink :refer [keyset seq-contains? parse-int order-by-expr?]]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(def query-params ["limit" "offset" "order_by" "include_total"])
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
  [{:keys [limit offset order_by] :as paging-options}]
  (and
   (map? paging-options)
   (or
    (nil? limit)
    (pos? limit))
   (or
    (nil? offset)
    (>= offset 0))
   (or
    (nil? order_by)
    (and
     (sequential? order_by)
     (every? order-by-expr? order_by)))))

(defn parse-order-by-json
  "Parses a JSON order_by string.  Returns the parsed string, or a Ring
  error response with a useful error message if there was a parse failure."
  [order_by]
  (try
    ;; If we don't force realization of parse-string right here, then
    ;; we will return a lazy sequence, which upon realization later
    ;; might throw an uncaught JsonParseException.
    (doall (json/parse-string order_by true))
    (catch JsonParseException e
      (throw (IllegalArgumentException.
              (str "Illegal value '" order_by "' for :order_by; expected a JSON "
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
  "Validates an order_by data structure.  The value must be `nil`, an empty list,
  or a list of maps.  Returns the input if validation is successful, or a
  Ring error response with a useful error message if the validation fails."
  [order_by]
  (if (or (empty? order_by)
          ((every-pred sequential? #(every? map? %)) order_by))
    order_by
    (throw (IllegalArgumentException.
            (str "Illegal value '" order_by "' for :order_by; expected "
                 "an array of maps.")))))

(defn parse-required-order-by-fields
  "Validates that each map in the order_by list contains the required
  key ':field', and a legal value for the optional key ':order' if it
  is provided.  Throws an exception with a useful error message
  if validation fails; otherwise, returns a list of order by expressions
  that satisfy `order-by-expr?`"
  [order_by]
  {:post [(every? order-by-expr? %)]}
  (when-let [bad-order-by (some
                           (fn [x] (when-not (contains? x :field) x))
                           order_by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order_by; "
                 "missing required key 'field'."))))
  (when-let [bad-order-by (some
                           (fn [x] (when-not (valid-order-str? (:order x)) x))
                           order_by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order_by; "
                 "'order' must be either 'asc' or 'desc'"))))
  (map
   (fn [x]
     [(keyword (:field x)) (parse-order-str (:order x))])
   order_by))

(defn validate-no-invalid-order-by-fields
  "Validates that each map in the order_by list does not contain any invalid
  keys.  Legal keys are ':field' and ':order'.  Returns the input if validation
  was successful; throws an exception with a useful error message otherwise."
  [order_by]
  (if-let [bad-order-by (some
                         (fn [x] (when (keys (dissoc x :field :order)) x))
                         order_by)]
    (throw (IllegalArgumentException.
            (str "Illegal value '" bad-order-by "' in :order_by; "
                 "unknown key '" (name (first (keys (dissoc bad-order-by :field :order)))) "'.")))
    order_by))

(defn parse-order-by'
  "Given a list of order-by clauses that conform to the PuppetDB
  paging API, validate and convert the order-by to our internal
  format. Incoming is of the form:

  {:field String (s/optional-key :order) String}

  outgoing is of the form:

  [(s/one s/Keyword \"field-name\") (s/one (s/enum :ascending :descending) \"sort-order\")]

  Not applying schema here until the original parse-order-by fn is
  replaced by this one. There are tests that require bad input
  parameters to be handled properly inside this function

  If validation fails, this function will throw an exception with
  an informative error message as to the cause of the failure."
  [order-by]
  {:post [(every? order-by-expr? %)]}
  (when order-by
    (->> order-by
         validate-order-by-data-structure
         validate-no-invalid-order-by-fields
         parse-required-order-by-fields)))

(defn parse-order-by
  "DEPRECATED - this function will be replaced by parse-order-by'
  Given a map of paging-options, validates that the order_by field conforms to
  our specifications:

  * Must be a JSON string
  * Must deserialize to an array of maps
  * Each map represents a field to order by in the final query; must contain
    a key 'field'
  * Each map may also contain the optional key 'order', but may not contain
    any other keys

  Assuming that these conditions are met, the function will return the paging-options
  map with an updated/sanitized version of the 'order_by' value; deserialized
  from JSON into a map, keywordized-keys, etc.

  If validation fails, this function will throw an exception with
  an informative error message as to the cause of the failure."
  {:deprecated "3.0.0"}
  [paging-options]
  {:post [(map? %)
          (= (keyset %) (keyset paging-options))
          (every? order-by-expr? (% :order_by))]}
  (if-let [order_by (paging-options :order_by)]
    (->> order_by
         (parse-order-by-json)
         parse-order-by'
         (assoc paging-options :order_by))
    paging-options))

(defn parse-count
  "Parse the optional `include_total` query parameter in the paging options map,
  and return an updated map with the correct boolean value."
  [paging-options]
  (let [count? (http/parse-boolean-query-param paging-options :include_total)]
    (-> paging-options
        (assoc :include_total count?))))

(defn coerce-to-int
  "Parses the int unless it's already an int"
  [i]
  (if (string? i)
    (parse-int i)
    i))

(defn validate-limit
  "Validates that the limit string is a positive non-zero integer. Returns the integer
  form if validation was successful, otherwise an IllegalArgumentException is thrown."
  [limit]
  {:pre  [(or (string? limit)
              (integer? limit))]
   :post [(and (integer? %) (> % 0))]}
  (let [l (coerce-to-int limit)]
    (if ((some-fn nil? neg? zero?) l)
      (throw (IllegalArgumentException.
              (format "Illegal value '%s' for :limit; expected a positive non-zero integer." limit)))
      l)))

(pls/defn-validated parse-limit' :- (s/maybe s/Int)
  "Parse the optional `limit` query parameter. Returns an integer
  version of `limit` upon successful validation. Throws an exception
  if provided `limit` is not a positive integer"
  [limit :- (s/maybe (s/either String s/Int))]
  (when limit (validate-limit limit)))

(defn parse-limit
  "DEPRECATED - this function will be replaced by parse-limit'

  Parse the optional `limit` query parameter in the paging options map,
  and return an updated map with the correct integer value.
  Throws an exception if the provided limit is not a positive non-zero integer."
  {:deprecated "3.0.0"}
  [paging-options]
  (update paging-options :limit parse-limit'))

(defn validate-offset
  "Validates that the offset string is a non-negative integer. Returns the integer
  form if validation was successful, otherwise an IllegalArgumentException is thrown."
  [offset]
  {:pre  [(or (integer? offset) (string? offset))]
   :post [(and (integer? %) (>= % 0))]}
  (let [o (coerce-to-int offset)]
    (if ((some-fn nil? neg?) o)
      (throw (IllegalArgumentException.
              (format "Illegal value '%s' for :offset; expected a non-negative integer." offset)))
      o)))

(defn parse-offset'
  "Parse the optional `offset` query parameter in the paging options map,
  and return an updated map with the correct integer value.
  Throws an exception if the provided offset is not a non-negative integer."
  [offset]
  (when offset (validate-offset offset)))

(defn parse-offset
  "DEPRECATED - this function will be replaced by parse-offset'
  Parse the optional `offset` query parameter in the paging options map,
  and return an updated map with the correct integer value.
  Throws an exception if the provided offset is not a non-negative integer."
  {:deprecated "3.0.0"}
  [paging-options]
  (update paging-options :offset parse-offset'))

(defn validate-order-by!
  "Given a list of keywords representing legal fields for ordering a query, and a map of
  paging options, validate that the order_by data in the paging options complies with
  the list of fields.  Throws an exception if validation fails."
  [columns paging-options]
  {:pre [(sequential? columns)
         (every? keyword? columns)
         ((some-fn nil? valid-paging-options?) paging-options)]}
  (doseq [field (map first (:order_by paging-options))]
    (when-not (seq-contains? columns field)
      (throw (IllegalArgumentException.
               (format "Unrecognized column '%s' specified in :order_by; Supported columns are '%s'"
                       (name field)
                       (string/join "', '" (map name columns)))))))
  paging-options)

(defn requires-paging?
  "Given a paging-options map, return true if the query requires paging
  and false if it does not."
  [{:keys [limit offset order_by include_total] :as paging-options}]
  (not
   (and
    (every? nil? [limit offset])
    ((some-fn nil? (every-pred coll? empty?)) order_by)
    (not include_total))))

(defn dealias-order-by
  [{:keys [projections] :as query-rec} paging-options]
  (let [alias-map (->> projections
                       (kitchensink/mapvals (comp h/extract-sql :field))
                       (kitchensink/mapkeys keyword))
        rename-field (fn [order-by-pair] (update order-by-pair 0 (partial get alias-map)))]
    (update paging-options :order_by (partial map rename-field))))
