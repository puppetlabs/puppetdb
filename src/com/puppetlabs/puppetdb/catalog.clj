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
;; 2. Tags are represented as lists (and may contain duplicates)
;;    instead of sets
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
            [com.puppetlabs.utils :as pl-utils])
  (:use [clojure.core.match :only [match]]))

(def ^:const catalog-version
  ^{:doc "Constant representing the version number of the PuppetDB
  catalog format"}
  (Integer. 2))

(def ^:const valid-relationships
  #{:contains :required-by :notifies :before :subscription-of})

(def ^:const catalog-attributes
  #{:certname :puppetdb-version :api-version :version :edges :resources})

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
  "Turns a resource's list of tags into a set of strings."
  [{:keys [tags] :as o}]
  {:pre [tags
         (every? string? tags)]
   :post [(set? (:tags %))]}
  (update-in o [:tags] set))

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

(defn validate-keys
  "Ensure that the set of keys in the catalog is exactly the set specified in `catalog-attributes`."
  [catalog]
  {:pre [(map? catalog)]
   :post [(= % catalog)]}
  (let [present-keys (pl-utils/keyset catalog)
        extra-keys (set/difference present-keys catalog-attributes)
        missing-keys (set/difference catalog-attributes present-keys)]
    (when-not (empty? extra-keys)
      (throw (IllegalArgumentException. (format "Catalog has unexpected keys: %s" (string/join ", " (map name extra-keys))))))
    (when-not (empty? missing-keys)
      (throw (IllegalArgumentException. (format "Catalog is missing keys: %s" (string/join ", " (map name missing-keys)))))))
  catalog)

(def validate
  (comp validate-edges validate-resources validate-keys))

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
          (= (:puppetdb-version %) catalog-version)
          (:certname %)
          (= (:certname %) (:name catalog))
          (= (:api-version %) (:api_version catalog))
          (number? (:api-version %))]}
  (-> catalog
    (update-in [:version] str)
    (assoc :puppetdb-version catalog-version)
    (set/rename-keys {:name :certname :api_version :api-version})))

(def transform
  "Applies every transformation to the catalog, converting it from wire format
  to our internal structure."
  (comp
    transform-edges
    transform-resources
    transform-metadata
    collapse))

;; ## Deserialization

(defmulti parse-catalog
  "Parse a wire-format `catalog` object or string of the specified `version`,
  returning a PuppetDB-suitable representation."
  (fn [catalog version]
    (match [catalog version]
           [(_ :when string?) _]
           String

           [(_ :when map?) (_ :when number?)]
           version

           [(_ :when map?) (_ :when (complement number?))]
           (throw (IllegalArgumentException. (format "Catalog version '%s' is not a legal version number" version)))

           ;; At this point, catalog can't be a string or a map (regardless of
           ;; what version is), so there's our problem!
           :else
           (throw (IllegalArgumentException. (format "Catalog must be specified as a string or a map, not '%s'" (class catalog)))))))

(defmethod parse-catalog String
  [catalog version]
  {:pre   [(string? catalog)]
   :post  [(map? %)]}
  (parse-catalog (json/parse-string catalog true) version))

;; v1 is the same as v2, except with classes and tags. So remove those, and be
;; on our merry way.
(defmethod parse-catalog 1
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (-> catalog
      (update-in [:data] dissoc :classes :tags :environment)
      (parse-catalog (inc version))))

(defmethod parse-catalog 2
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (validate (transform catalog)))

(defmethod parse-catalog :default
  [catalog version]
  (throw (IllegalArgumentException. (format "Unknown catalog version '%s'" version))))
