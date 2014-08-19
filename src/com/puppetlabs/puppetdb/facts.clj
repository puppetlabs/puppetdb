(ns com.puppetlabs.puppetdb.facts
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.cheshire :as json]
            [schema.core :as s]
            [com.puppetlabs.puppetdb.schema :as pls]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.scf.hash :as hash]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [com.puppetlabs.puppetdb.utils :as utils]
            [clojure.edn :as clj-edn]))

;; SCHEMA

(def fact-path-element
  (s/either s/Str s/Num))

(def fact-path
  [fact-path-element])

(def fact-path-map
  {:path s/Str
   :depth s/Int
   :name s/Str
   :value_hash s/Str
   :value_float (s/maybe Double)
   :value_string (s/maybe s/Str)
   :value_integer (s/maybe s/Int)
   :value_boolean (s/maybe s/Bool)
   :value_json (s/maybe s/Str)
   :value_type_id s/Int})

(def fact-set
  {s/Str s/Any})

;; GLOBALS

(def factpath-delimiter
  "#~")

;; FUNCS

(pls/defn-validated escape-delimiter :- s/Str
  "Escape the delimiter from a string"
  [element :- s/Str]
  (str/replace element #"(\\{0,1}#)(\\*\\{0,1}~)" "\\\\$1\\\\$2"))

(pls/defn-validated unescape-delimiter :- s/Str
  "Un-escape the delimiter from a string"
  [element :- s/Str]
  (str/replace element #"(\\*)\\#(\\*)\\~" "$1#$2~"))

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
  (let [parts (str/split s (re-pattern factpath-delimiter))]
    (map unencode-path-segment parts)))

(pls/defn-validated factpath-to-string :- s/Str
  "Converts a `fact-path` to an encoded string ready for database storage."
  [factpath :- fact-path]
  (let [encodedpath (map encode-factpath-element factpath)]
    (str/join factpath-delimiter encodedpath)))

(pls/defn-validated value-type-id :- s/Int
  "Given a piece of standard hierarchical data, returns the type as an id."
  [data :- s/Any]
  (cond
   (keyword? data) 0
   (string? data) 0
   (integer? data) 1
   (float? data) 2
   (kitchensink/boolean? data) 3
   (nil? data) 4
   (coll? data) 5))

(defn factmap-express-node
  "Convert data and path into a node definition ready for storage."
  [data path]
  (let [type-id (value-type-id data)
        initial-map {:path (factpath-to-string path)
                     :name (first path)
                     :depth (dec (count path))
                     :value_type_id type-id
                     :value_hash (hash/generic-identity-hash data)
                     :value_string nil
                     :value_integer nil
                     :value_float nil
                     :value_boolean nil
                     :value_json nil}]
    (if (nil? data)
      initial-map
      (let [value-keyword (case type-id
                            0 :value_string
                            1 :value_integer
                            2 :value_float
                            3 :value_boolean
                            5 :value_json)
            data (if (coll? data)
                   (sutils/db-serialize data)
                   data)]
        (assoc initial-map value-keyword data)))))

(defn factmap-to-paths*
  "Recursive function, when given some structured data it will descend into
   children building up the path until an outer leaf is reached, returning the
   final built up list of paths as a result."
  ([data] (factmap-to-paths* data [] []))
  ;; We specifically do not validate with schema here, for performance.
  ([data mem path]
     (let [depth (dec (count path))]
       (if (coll? data)
         ;; Branch
         (if (empty? data)
           mem
           (let [idv (if (map? data)
                       (into [] data)
                       (map vector (iterate inc 0) data))]
             (loop [[k v] (first idv)
                    remaining (next idv)
                    ;; We add this branch to the mem if we are depth 0
                    ;; thus allowing us to store the top level for each
                    ;; fact.
                    fp (if (= depth 0)
                         (conj mem (factmap-express-node data path))
                         mem)]
               (let [new-fp (factmap-to-paths* v fp (conj path k))]
                 (if (empty? remaining)
                   new-fp
                   (recur (first remaining)
                          (next remaining)
                          new-fp))))))
         ;; Leaf
         (conj mem (factmap-express-node data path))))))

(pls/defn-validated factmap-to-paths :- [fact-path-map]
  "Converts a map of facts to a list of `fact-path-map`s."
  [hash :- fact-set]
  (factmap-to-paths* hash))

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

   Returns a string that contains a formatted regexp."
  [rearray :- fact-path]
  (str "^"
       (-> rearray
           factpath-regexp-elements-to-regexp
           factpath-to-string)
       "$"))

(defn convert-row-type
  "Coerce the value of a row to the proper type."
  [dissociated-fields row]
  (let [conversion (case (:type row)
                     "boolean" clj-edn/read-string
                     "float" (comp double clj-edn/read-string)
                     "integer" (comp biginteger clj-edn/read-string)
                     "json" json/parse-string
                     ("string" "null") identity)]
    (reduce #(dissoc %1 %2)
            (update-in row [:value] conversion)
            dissociated-fields)))
