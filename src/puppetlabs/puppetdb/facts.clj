(ns puppetlabs.puppetdb.facts
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.string :as str]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
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
   :value-hash s/Str
   :value-float (s/maybe Double)
   :value-string (s/maybe s/Str)
   :value-integer (s/maybe s/Int)
   :value-boolean (s/maybe s/Bool)
   :value-json (s/maybe s/Str)
   :value-type-id s/Int})

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
                     :value-type-id type-id
                     :value-hash (hash/generic-identity-hash data)
                     :value-string nil
                     :value-integer nil
                     :value-float nil
                     :value-boolean nil
                     :value-json nil}]
    (if (nil? data)
      initial-map
      (let [value-keyword (case type-id
                            0 :value-string
                            1 :value-integer
                            2 :value-float
                            3 :value-boolean
                            5 :value-json)
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
  "Converts a field found in a factpath regexp to its equivalent regexp."
  [rearray :- fact-path]
  (map (fn [element]
         (if (string? element)
           (-> element
               ;; This ensures that any of the more wildcard searches do not cross
               ;; delimiter boundaries, by wrapping them with a negative lookup
               ;; ahead for the delimiter.
               ;; PostgreSQL look-aheads cause it to fail with the * quantifier, so this
               ;; fakes it.
               (str/replace #"\.\*" (format "(?:((?!%s).)+|.?)" factpath-delimiter))
               (str/replace #"\.(\+|\{.+\})" (format "(?:(?!%s).)$1" factpath-delimiter)))
           element))
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
                     "float" (constantly (:value-float row))
                     "integer" (constantly (:value-integer row))
                     "json" json/parse-string
                     ("string" "null") identity
                     identity)]
    (reduce #(dissoc %1 %2)
            (utils/update-when row [:value] conversion)
            dissociated-fields)))
