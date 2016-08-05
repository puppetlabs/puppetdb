(ns puppetlabs.puppetdb.facts
  (:require [clojure.edn :as clj-edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; SCHEMA

(def fact-path-element
  (s/cond-pre s/Str s/Num))

(def fact-path
  [fact-path-element])

(def pathmap-schema
  {:path s/Str
   :name s/Str
   :depth s/Int})

(def fact-set-schema
  {s/Str s/Any})

(def facts-schema
  {:certname String
   :values fact-set-schema
   :timestamp pls/Timestamp
   :environment (s/maybe s/Str)
   :producer_timestamp (s/cond-pre s/Str pls/Timestamp)
   :producer (s/maybe s/Str)})

(def valuemap-schema
  {:value_hash s/Str
   :value_float (s/maybe Double)
   :value_string (s/maybe s/Str)
   :value_integer (s/maybe s/Int)
   :value_boolean (s/maybe s/Bool)
   :value (s/maybe s/Any)
   :value_type_id s/Int})

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

(def escape-quoted-num
  (re-pattern "^\"\\d+\"$"))

(defn unencode-path-segment
  "Attempt to coerce string to number, otherwise unescape."
  [^String s]
  (if-let [num (str->num s)]
    num
    (if (re-matches escape-quoted-num s)
      (subs s 1 (dec (.length s)))
      (unescape-delimiter s))))

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

(defn value->valuemap
  [value]
  (let [type-id (value-type-id value)
        initial-map {:value_type_id type-id
                     :value_hash (hash/generic-identity-hash value)
                     :value_string nil
                     :value_integer nil
                     :value_float nil
                     :value_boolean nil
                     :value nil}]
    (if (nil? value)
      initial-map
      (let [value-keyword (case type-id
                            0 :value_string
                            1 :value_integer
                            2 :value_float
                            3 :value_boolean
                            5 :value)]
        (assoc initial-map value-keyword value
          :value (sutils/munge-jsonb-for-storage value))))))

(defn flatten-facts-with
  "Returns a collection of (leaf-fn path leaf) for all of the paths
  represented by facts."
  ;; Used by migration-legacy, so copy this function there before
  ;; making backward-incompatible changes.
  ([leaf-fn facts] (flatten-facts-with leaf-fn facts [] []))
  ;; We intentionally do not validate with schema here, for performance.
  ([leaf-fn data mem path]
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
                         (conj mem (leaf-fn path data))
                         mem)]
               (let [new-fp (flatten-facts-with leaf-fn v fp (conj path k))]
                 (if (empty? remaining)
                   new-fp
                   (recur (first remaining)
                          (next remaining)
                          new-fp))))))
         ;; Leaf
         (conj mem (leaf-fn path data))))))

(pls/defn-validated path->pathmap :- pathmap-schema
  [path :- fact-path]
  ;; Used by migration-legacy, so copy this function there before
  ;; making backward-incompatible changes.
  {:path (factpath-to-string path)
   :name (first path)
   :depth (dec (count path))})

(pls/defn-validated facts->paths-and-valuemaps
  :- [(s/pair fact-path "path" valuemap-schema "valuemap")]
  "Returns [path valuemap] pairs for all
  facts. i.e. ([\"foo#~bar\" vm] ...)"
  [facts :- fact-set-schema]
  (flatten-facts-with (fn [fp leaf] [fp (value->valuemap leaf)]) facts))

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

 (defn wire-v4->wire-v5
   "Takes a v4 formatted replace facts command and upgrades it to a v5 facts command"
   [facts]
   (assoc facts :producer nil))

(defn wire-v3->wire-v5
  "Takes a v3 formatted replace facts command and upgrades it to a v5 facts command"
  [facts]
  (-> facts
      (set/rename-keys {:producer-timestamp :producer_timestamp
                            :name :certname})
      wire-v4->wire-v5))

(defn wire-v2->wire-v5
  "Takes a v2 formatted replace facts command and upgrades it to a v5 facts command"
  [facts received-time]
  (-> facts
      (assoc :producer-timestamp received-time)
      wire-v3->wire-v5))
