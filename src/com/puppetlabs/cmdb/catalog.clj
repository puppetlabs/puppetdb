;; ## Puppet catalog parsing
;;
;; Puppet catalogs aren't really pre-configured for easy persistence
;; and manipulation; while they contain complete records of all
;; resources and edges, and most things are properly encoded as lists
;; or maps, there are still a number of places where structure is
;; absent or lacking:
;;
;; 1. Resource specifiers are represented as opaque strings, like
;;    `Class[Foobar]`, as opposed to something like
;;    `{"type" "Class" "title" "Foobar"}`
;;
;; 2. Tags and classes are represented as lists (and may contain
;;    duplicates) instead of sets
;;
;; 3. Resources are represented as a list instead of a map, making
;;    operations that need to correlate against specific resources
;;    unneccesarily difficult
;;
;; The functions in this namespace are designed to take a wire-format
;; catalog and restructure it to fix the above problems and, in
;; general, make catalogs more easily manipulatable by Clojure code.
;;
;; ### Terminology
;;
;; There are a few catalog-specific terms that we use throughout the
;; codebase:
;;
;; ### Resource Specifier (resource-spec)
;;
;; A map of the form `{:type "Class" :title "Foobar"}`. This is a
;; unique identifier for a resource within a catalog.
;;
;; ### Resource
;;
;; A map that represents a single resource in a catalog:
;;
;;     {:type       "..."
;;      :title      "..."
;;      :...        "..."
;;      :tags       #{"tag1", "tag2", ...}
;;      :parameters {"name1" "value1"
;;                   "name2" "value2"
;;                   ...}}
;;
;; Certain attributes are treated special:
;;
;; * `:type` and `:title` are used to produce a `resource-spec` for
;;   this resource
;;
;; * parameters signifying ordering (`subscribe`, `before`, `require`,
;;   etc) are used to create dependency specifications
;;
;; ### Dependency Specification
;;
;; A representation of an "edge" in the catalog. All edges have the
;; following form:
;;
;;     {:source       <resource spec>
;;      :target       <resource spec>
;;      :relationship <relationship id>}
;;
;; A relationship identifier can be one of:
;;
;; * `:contains`
;; * `:required-by`
;; * `:notifies`
;; * `:before`
;; * `:subscription-of`
;;
;; ### CMDB catalog
;;
;; A wire-format-neutral representation of a Puppet catalog. It is a
;; map with the following structure:
;;
;;     {:certname    "..."
;;      :api-version "..."
;;      :version     "..."
;;      :classes     #("class1", "class2", ...)
;;      :tags        #("tag1", "tag2", ...)
;;      :resources   {<resource-spec> <resource>
;;                    <resource-spec> <resource>
;;                    ...}
;;      :edges       #(<dependency-spec>,
;;                     <dependency-spec>,
;;                     ...)}
;;
(ns com.puppetlabs.cmdb.catalog
  (:require [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [digest]
            [com.puppetlabs.utils :as pl-utils]))

(def CMDB-VERSION
  ^{:doc "Constant representing the current version number of the CMDB
  catalog format"}
  (Integer. 1))

;; ## Utiltity functions

(defn resource-spec-to-map
  "Convert a textual resource specifier like `\"Class[foo]\"` into a map
  of the form `{:type \"Class\" :title \"foo\"}`"
  [str]
  {:pre [(string? str)]
   :post [(map? %)
          (:type %)
          (:title %)]}
  (let [[[_ type title]] (re-seq #"(?s)(^.*?)\[(.*)\]$" str)]
    {:type type :title title}))

(defn keys-to-keywords
  "Take a map with string keys and return a map with those keys turned
  into keywords"
  [m]
  {:pre  [(map? m)]
   :post [(map? %)
          (every? keyword? (keys %))]}
  (into {} (for [[k v] m]
             [(keyword k) v])))

;; ## Edge normalization

(defn keywordify-edges
  "Take each edge in the the supplied catalog, and make all of their
  keys proper keywords"
  [{:keys [edges] :as catalog}]
  {:pre  [(coll? edges)
          (every? string? (mapcat keys edges))]
   :post [(every? keyword? (mapcat keys (% :edges)))]}
  (let [new-edges (for [{:strs [source target relationship]} edges]
                    {:source (keys-to-keywords source)
                     :target (keys-to-keywords target)
                     :relationship (keyword relationship)})]
    (assoc catalog :edges (set new-edges))))

;; ## Resource normalization

(defn keywordify-resource
  "Takes all the keys of each resource and convert them to proper
  clojure keywords, doing intermediate data transforms in the process.

  transformations we do:

  1. convert each resource's list of tags into a set of tags"
  [{:strs [tags] :as resource}]
  (let [new-resource (keys-to-keywords resource)
        new-tags     (set tags)]
    (assoc new-resource :tags new-tags)))

(defn keywordify-resources
  "Applies keywordify-resource to each resource in the supplied catalog,
  returning a new catalog with its list of resources appropriately
  transformed."
  [{:keys [resources] :as catalog}]
  {:pre [(coll? resources)
         (not (map? resources))]}
  (let [new-resources (map keywordify-resource resources)]
    (assoc catalog :resources new-resources)))

(defn mapify-resources
  "Turns the list of resources into a mapping of
  `{resource-spec resource, resource-spec resource, ...}`"
  [{:keys [resources] :as catalog}]
  {:pre  [(coll? resources)
          (not (map? resources))]
   :post [(map? (:resources %))
          (= (count resources) (count (:resources %)))]}
  (let [new-resources (into {} (for [{:keys [type title] :as resource} resources]
                                 [{:type type :title title} resource]))]
    (assoc catalog :resources new-resources)))

;; ## Misc normalization routines

(defn setify-tags-and-classes
  "Turns the catalog's list of tags and classes into proper sets"
  [{:keys [classes tags] :as catalog}]
  {:pre [classes tags]}
  (assoc catalog
    :classes (set classes)
    :tags (set tags)))

;; ## Integrity checking
;;
;; Functions to ensure that the catalog structure is coherent.

(defn check-edge-integrity
  "Ensure that all edges have valid sources and targets, and that the
  relationship types are acceptable."
  [{:keys [edges resources] :as catalog}]
  {:pre [(set? edges)
         (map? resources)]}
  (doseq [{:keys [source target relationship] :as edge} edges
          resource [source target]]
    (when-not (resources resource)
      (throw (IllegalArgumentException.
              (format "Edge '%s' refers to resource '%s', which doesn't exist in the catalog." edge resource)))))
  catalog)

;; ## High-level parsing routines

(defn restructure-catalog
  "Given a wire-format catalog, restructure it to conform to cmdb format.

  This primarily consists of hoisting certain catalog attributes from
  nested structures to instead be 'top-level'."
  [wire-catalog]
  {:pre  [(map? wire-catalog)]
   :post [(map? %)
          (:certname %)
          (number? (:api-version %))
          (:version %)]}
  (-> (wire-catalog "data")
      (keys-to-keywords)
      (dissoc :name)
      (assoc :cmdb-version CMDB-VERSION)
      (assoc :api-version (get-in wire-catalog ["metadata" "api_version"]))
      (assoc :certname (get-in wire-catalog ["data" "name"]))
      (assoc :version (str (get-in wire-catalog ["data" "version"])))))

;; ## Deserialization
;;
;; _TODO: we should change these to multimethods_

(defn parse-from-json-obj
  "Parse a wire-format JSON catalog object contained in `o`, returning a
  cmdb-suitable representation."
  [o]
  {:post [(map? %)]}
  (-> o
      (restructure-catalog)
      (keywordify-resources)
      (keywordify-edges)
      (mapify-resources)
      (setify-tags-and-classes)
      (check-edge-integrity)))

(defn parse-from-json-string
  "Parse a wire-format JSON catalog string contained in `s`, returning a
  cmdb-suitable representation."
  [s]
  {:pre  [(string? s)]
   :post [(map? %)]}
  (-> (json/parse-string s)
      (parse-from-json-obj)))

(defn parse-from-json-file
  "Parse a wire-format JSON catalog located at `filename`, returning a
  cmdb-suitable representation."
  [filename]
  (parse-from-json-string (slurp filename)))
