;; ## Puppet catalog parsing
;;
;; Functions that handle conversion of catalogs from wire format to
;; internal PuppetDB format.
;;
;; The wire format is described in detail in [the
;; spec](../spec/catalog-wire-format.md).
;;
;; There are a number of transformations we apply to wire format
;; catalogs during conversion to our internal format; while wire
;; format catalogs contain complete records of all resources and
;; edges, and most things are properly encoded as lists or maps, there
;; are still a number of places where structure is absent or lacking:
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
;; 4. Keys to all maps are strings (to conform with JSON), instead of
;;    more convenient Clojure keywords
;;
;; ### Terminology
;;
;; Unless otherwise indicated, all terminology for catalog components
;; matches terms listed in [the spec](../spec/catalog-wire-format.md).
;;
;; ### Transformed constructs
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
;;      :parameters {:name1 "value1"
;;                   :name2 "value2"
;;                   ...}}
;;
;; Certain attributes are treated special:
;;
;; * `:type` and `:title` are used to produce a `resource-spec` for
;;   this resource
;;
;; ### Edge
;;
;; A representation of an "edge" (dependency or containment) in the
;; catalog. All edges have the following form:
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
;; ### Catalog
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
(ns com.puppetlabs.puppetdb.catalog
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [cheshire.core :as json]
            [digest]
            [com.puppetlabs.utils :as pl-utils]))

(def CATALOG-VERSION
  ^{:doc "Constant representing the version number of the PuppetDB
  catalog format"}
  (Integer. 1))

(def valid-relationships
  #{:contains :required-by :notifies :before :subscription-of})

;; ## Utiltity functions

(defn resource-spec-to-map
  "Convert a textual resource specifier like `\"Class[foo]\"` into a map
  of the form `{:type \"Class\" :title \"foo\"}`"
  [str]
  {:pre  [(string? str)]
   :post [(map? %)
          (:type %)
          (:title %)]}
  (let [[[_ type title]] (re-seq #"(?s)(^.*?)\[(.*)\]$" str)]
    {:type type :title title}))

;; ## Edge normalization

(defn transform-edge*
  "Converts the `relationship` value of `edge` into a
  keyword."
  [edge]
  {:pre [(:relationship edge)]
   :post [(keyword? (:relationship %))]}
  (update-in edge [:relationship] keyword))

(defn transform-edges
  "Transforms every edge of the given `catalog` and converts the edges into a set."
  [{:keys [edges] :as catalog}]
  {:pre  [(coll? edges)]
   :post [(set? (:edges %))
          (every? keyword? (map :relationship (:edges %)))]}
  (assoc catalog :edges (set (map transform-edge* edges))))
;;
;; ## Misc normalization routines

(defn transform-tags
  "Turns an object's (either catalog or resource) list of tags into a set of
  strings."
  [{:keys [tags] :as o}]
  {:pre [tags
         (every? string? tags)]
   :post [(set? (:tags %))]}
  (update-in o [:tags] set))

(defn transform-classes
  "Turns the catalog's list of classes into a set of strings."
  [{:keys [classes] :as catalog}]
  {:pre [classes
         (every? string? classes)]
   :post [(set? (:classes %))]}
  (update-in catalog [:classes] set))

;; ## Resource normalization

(defn transform-resource*
  "Normalizes the structure of a single `resource`. Right now this just means
  setifying the tags."
  [resource]
  {:pre [(map? resource)]
   :post [(set? (:tags %))]}
  (transform-tags resource))

(defn transform-resources
  "Turns the list of resources into a mapping of
  `{resource-spec resource, ...}`, as well as transforming each resource."
  [{:keys [resources] :as catalog}]
  {:pre  [(coll? resources)
          (not (map? resources))]
   :post [(map? (:resources %))
          (= (count resources) (count (:resources %)))]}
  (let [new-resources (into {} (for [{:keys [type title] :as resource} resources
                                     :let [resource-spec    {:type type :title title}
                                           new-resource     (transform-resource* resource)]]
                                 [resource-spec new-resource]))]
    (assoc catalog :resources new-resources)))

;; ## Integrity checking
;;
;; Functions to ensure that the catalog structure is coherent.

(def ^:const tag-pattern
  #"\A[a-z0-9_][a-z0-9_:\-.]*\Z")

(defn validate-tags
  "Ensure that all catalog tags conform to the allowed tag pattern."
  [{:keys [tags] :as catalog}]
  {:pre [(set? tags)]
   :post [(= % catalog)]}
  (when-let [invalid-tag (first
                           (remove #(re-find tag-pattern %) tags))]
    (throw (IllegalArgumentException.
             (format "Catalog contains an invalid tag '%s'. Tags must match the pattern /%s/." invalid-tag tag-pattern))))
  catalog)

(defn validate-resources
  "Ensure that all resource tags conform to the allowed tag pattern."
  [{:keys [resources] :as catalog}]
  {:pre [(map? resources)]
   :post [(= % catalog)]}
  (doseq [[resource-spec resource] resources]
    (when-let [invalid-tag (first
                             (remove #(re-find tag-pattern %) (:tags resource)))]
      (throw (IllegalArgumentException.
               (format "Resource '%s' has an invalid tag '%s'. Tags must match the pattern /%s/." resource-spec invalid-tag tag-pattern)))))
  catalog)

(defn validate-edges
  "Ensure that all edges have valid sources and targets, and that the
  relationship types are acceptable."
  [{:keys [edges resources] :as catalog}]
  {:pre [(set? edges)
         (map? resources)]
   :post [(= % catalog)]}
  (doseq [{:keys [source target relationship] :as edge} edges
          resource [source target]]
    (when-not (resources resource)
      (throw (IllegalArgumentException.
               (format "Edge '%s' refers to resource '%s', which doesn't exist in the catalog." edge resource))))
    (when-not (valid-relationships relationship)
      (throw (IllegalArgumentException.
               (format "Edge '%s' has invalid relationship type '%s'" edge relationship)))))
  catalog)

;; ## High-level parsing routines

(defn collapse
  "Combines the `data` and `metadata` section of the given `catalog` into a
  single map."
  [{:keys [metadata data] :as catalog}]
  {:pre [(map? metadata)
         (map? data)
         (empty? (set/intersection (pl-utils/keyset metadata) (pl-utils/keyset data)))]
   :post [(map? %)]}
  (merge metadata data))

(defn transform-metadata
  "Standardizes the metadata in the given `catalog`. In particular:
    * Stringifies the `version`
    * Adds a `puppetdb-version` with the current catalog format version
    * Renames `api_version` to `api-version`
    * Renames `name` to `certname`"
  [catalog]
  {:pre [(map? catalog)]
   :post [(string? (:version %))
          (= (:puppetdb-version %) CATALOG-VERSION)
          (:certname %)
          (= (:certname %) (:name catalog))
          (= (:api-version %) (:api_version catalog))
          (number? (:api-version %))]}
  (-> catalog
    (update-in [:version] str)
    (assoc :puppetdb-version CATALOG-VERSION)
    (set/rename-keys {:name :certname :api_version :api-version})))

(def transform
  "Applies every transformation to the catalog, converting it from wire format
  to our internal structure."
  (comp
    transform-edges
    transform-resources
    transform-classes
    transform-tags
    transform-metadata
    collapse))

(def validate
  "Applies every validation step to the catalog."
  (comp validate-edges validate-resources validate-tags))

;; ## Deserialization
;;
;; _TODO: we should change these to multimethods_

(def parse-from-json-obj
  "Parse a wire-format JSON catalog object contained in `o`, returning a
  puppetdb-suitable representation."
  (comp validate transform))

(defn parse-from-json-string
  "Parse a wire-format JSON catalog string contained in `s`, returning a
  puppetdb-suitable representation."
  [s]
  {:pre  [(string? s)]
   :post [(map? %)]}
  (parse-from-json-obj (json/parse-string s true)))

(defn parse-from-json-file
  "Parse a wire-format JSON catalog located at `filename`, returning a
  puppetdb-suitable representation."
  [filename]
  (parse-from-json-string (slurp filename)))
