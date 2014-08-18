(ns com.puppetlabs.puppetdb.facts
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.cheshire :as json]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.schema :as pls]
            [clojure.string :as string]
            [com.puppetlabs.puppetdb.zip :as zip]
            [com.puppetlabs.puppetdb.scf.hash :as hash]
            [com.puppetlabs.puppetdb.utils :as utils]
            [clojure.edn :as clj-edn]))

;; SCHEMA

(def fact-path-element
  (s/either s/Str s/Num))

(def fact-path
  [fact-path-element])

(def fact-value
  (s/maybe (s/either s/Keyword s/Str s/Num s/Bool)))

(def fact-path-map
  {:path s/Str
   :depth s/Int
   :name s/Str
   :value_hash s/Str
   :value_float (s/maybe Double)
   :value_string (s/maybe s/Str)
   :value_integer (s/maybe s/Int)
   :value_boolean (s/maybe s/Bool)
   :value_type_id s/Int})

(def fact-set
  {s/Str s/Any})

;; GLOBALS

(def factpath-delimiter
  "#~")

;; FUNCS

(pls/defn-validated flatten-fact-value :- s/Str
  "Flatten a fact value to a string either using JSON or coercement depending on
  the type."
  [value :- s/Any]
  (cond
   (string? value) value
   (kitchensink/boolean? value) (str value)
   (integer? value) (str value)
   (float? value) (str value)
   (map? value) (json/generate-string value)
   (coll? value) (json/generate-string value)
   :else (throw (IllegalArgumentException. (str "Value " value " is not valid for flattening")))))

(pls/defn-validated flatten-fact-set :- {s/Str s/Str}
  "Flatten a map of facts depending on the type of the value."
  [factset :- fact-set]
  (reduce-kv (fn [acc k v]
               (assoc acc k (flatten-fact-value v)))
             {} factset))

(pls/defn-validated escape-delimiter :- s/Str
  "Escape the delimiter from a string"
  [element :- s/Str]
  (string/replace element #"(\\{0,1}#)(\\*\\{0,1}~)" "\\\\$1\\\\$2"))

(pls/defn-validated unescape-delimiter :- s/Str
  "Un-escape the delimiter from a string"
  [element :- s/Str]
  (string/replace element #"(\\*)\\#(\\*)\\~" "$1#$2~"))

(pls/defn-validated quote-integer-strings :- s/Str
  "Surround a string with quotes if it looks like a number."
  [string :- s/Str]
  (if (every? utils/digit? string)
    (str "\"" string "\"")
    string))

(defn unescape-string
  "Strip escaped quotes from a string."
  [^String s]
  (if (= \" (.charAt s 0))
    (subs s 1 (dec (.length s)))
    s))

(pls/defn-validated encode-factpath-element :- s/Str
  "Converts a fact-path-element to an encoded string ready for database storage."
  [element :- fact-path-element]
  (str
   (if (string? element)
     ;; Strings are quoted (if number strings) and delimiters are escaped
     (-> (quote-integer-strings element)
         escape-delimiter)
     ;; Numbers are stored as-is
     element)))

(defn maybe-num-string?
  "Return true if first character of string is a digit."
  [^String k]
  (case (.charAt k 0)
    (\0 \1 \2 \3 \4 \5 \6 \7 \8 \9) true
    false))

(defn str->num
  "Attempt to coerce a string to a number, otherwise return nil."
  [^String s]
  (when (maybe-num-string? s)
    (try
      (Long/valueOf s)
      (catch Exception e
        nil))))

(defn unencode-path-segment
  "Attempt to coerce string to number, otherwise unescape."
  [^String s]
  (if-let [num (str->num s)]
    num
    (-> s
        unescape-string
        unescape-delimiter)))

(pls/defn-validated string-to-factpath :- fact-path
  "Converts a database encoded string back to a factpath."
  [s :- s/Str]
  (let [parts (string/split s (re-pattern factpath-delimiter))]
    (map unencode-path-segment parts)))

(pls/defn-validated factpath-to-string :- s/Str
  "Converts a `fact-path` to an encoded string ready for database storage."
  [factpath :- fact-path]
  (let [encodedpath (map encode-factpath-element factpath)]
    (string/join factpath-delimiter encodedpath)))

(pls/defn-validated value-type-id :- s/Int
  "Given a piece of standard hierarchical data, returns the type as an id."
  [data :- fact-value]
  (cond
   (keyword? data) 0
   (string? data) 0
   (integer? data) 1
   (float? data) 2
   (kitchensink/boolean? data) 3
   (nil? data) 4))

(defn factmap-to-paths*
  "Recursive function, when given some structured data it will descend into
   children building up the path until an outer leaf is reached, returning the
   final built up list of paths as a result."
  ([data] (factmap-to-paths* data [] []))
  ;; We specifically do not validate with schema here, for performance.
  ([data mem path]
     (if (coll? data)
       ;; Branch
       (if (empty? data)
           mem
           (let [idv (if (map? data)
                       (into [] data)
                       (map vector (iterate inc 0) data))]
             (loop [[k v] (first idv)
                    remaining (next idv)
                    fp mem]
               (let [new-fp (factmap-to-paths* v fp (conj path k))]
                 (if (empty? remaining)
                   new-fp
                   (recur (first remaining)
                          (next remaining)
                          new-fp))))))
       ;; Leaf
       (let [type-id (value-type-id data)
             initial-map {:path (factpath-to-string path)
                          :name (first path) 
                          :depth (dec (count path))
                          :value_type_id type-id
                          :value_hash (hash/generic-identity-hash data)
                          :value_string nil
                          :value_integer nil
                          :value_float nil
                          :value_boolean nil}
             final-map (if (nil? data)
                         initial-map
                         (let [value-keyword (case type-id
                                               0 :value_string
                                               1 :value_integer
                                               2 :value_float
                                               3 :value_boolean)]
                           (assoc initial-map value-keyword data)))]
         (conj mem final-map)))))

(pls/defn-validated factmap-to-paths :- [fact-path-map]
  "Converts a map of facts to a list of `fact-path-map`s."
  [hash :- fact-set]
  (factmap-to-paths* hash))

(defn factname-certname-pred
  "Create a function to compare the factnames in a list of rows
  with that of the first row."
  [rows]
  (let [factname (:name (first rows))
        certname (:certname (first rows))]
    (fn [row]
      (and (= factname (:name row))
           (= certname (:certname row))))))

(defn create-certname-pred
  "Create a function to compare the certnames in a list of
  rows with that of the first row."
  [rows]
  (let [certname (:certname (first rows))]
    (fn [row]
      (= certname (:certname row)))))

(defn int-map->vector
  "Convert a map of form {1 'a' 0 'b' ...} to vector ['b' 'a' ...]"
  [node]
  (when (map? node)
    (let [int-keys (keys node)]
      (when (every? integer? int-keys)
        (mapv node (sort int-keys))))))

(defn int-maps->vectors
  "Walk a structured fact set, transforming all int maps."
  [facts]
  (:node (zip/post-order-transform (zip/tree-zipper facts)
                                   [int-map->vector])))

(defn recreate-fact-path
  "Produce the nested map corresponding to a path/value pair.

   Operates by accepting an existing map `acc` and a map containing keys `path`
   and `value`, it splits the path into its components and populates the data
   structure with the `value` in the correct path.

   Returns the complete map structure after this operation is applied to
   `acc`."
  [acc {:keys [path value]}]
  (let [split-path (string-to-factpath path)]
    (assoc-in acc split-path value)))

(pls/defn-validated unstringify-value
  "Converts a stringified value from the database into its real value and type.

   Accepts either a string or a nil as input values."
  [type :- s/Str
   value :- (s/maybe s/Str)]
  (case type
    "boolean" (clj-edn/read-string value)
    "float" (-> value
                clj-edn/read-string
                double)
    "integer" (-> value
                  clj-edn/read-string
                  biginteger)
    value))

(pls/defn-validated factpath-regexp-elements-to-regexp :- fact-path
  "Converts a field found in a factpath regexp to its equivalent regexp"
  [rearray :- fact-path]
  (map (fn [element]
         (format "(?:(?!%s)%s)" factpath-delimiter element))
       rearray))

(pls/defn-validated factpath-regexp-to-regexp :- s/Str
  "Converts a regexp array to a single regexp for querying against the database.

   Returns a string that contains a fomatted regexp."
  [rearray :- fact-path]
  (str "^"
       (-> rearray
           factpath-regexp-elements-to-regexp
           factpath-to-string)
       "$"))

(defn augment-paging-options
  [{:keys [order-by] :as paging-options} entity]
  (if (or (nil? entity) (nil? order-by)) paging-options
    (let [[to-dissoc to-append] (case entity
                                  :facts     [:value
                                              [[:name :ascending]
                                               [:certname :ascending]]]
                                  :factsets  [nil
                                              [[:certname :ascending]]])
          to-prepend (filter #(not (= to-dissoc (first %))) order-by)]
        (assoc paging-options :order-by (concat to-prepend to-append)))))
