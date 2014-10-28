(ns puppetlabs.puppetdb.catalogs
  "Puppet catalog parsing

   Functions that handle conversion of catalogs from wire format to
   internal PuppetDB format.

   The wire format is described in detail in [the spec](../spec/catalog-wire-format.md).

   There are a number of transformations we apply to wire format
   catalogs during conversion to our internal format; while wire
   format catalogs contain complete records of all resources and
   edges, and most things are properly encoded as lists or maps, there
   are still a number of places where structure is absent or lacking:

   1. Resource specifiers are represented as opaque strings, like
      `Class[Foobar]`, as opposed to something like
      `{\"type\" \"Class\" \"title\" \"Foobar\"}`

   2. Tags are represented as lists (and may contain duplicates)
      instead of sets

   3. Resources are represented as a list instead of a map, making
      operations that need to correlate against specific resources
      unneccesarily difficult

   4. Keys to all maps are strings (to conform with JSON), instead of
      more convenient Clojure keywords

   ### Terminology

   Unless otherwise indicated, all terminology for catalog components
   matches terms listed in [the spec](../spec/catalog-wire-format.md).

   ### Transformed constructs

   ### Resource Specifier (resource-spec)

   A map of the form `{:type \"Class\" :title \"Foobar\"}`. This is a
   unique identifier for a resource within a catalog.

   ### Resource

   A map that represents a single resource in a catalog:

       {:type       \"...\"
        :title      \"...\"
        :...        \"...\"
        :tags       #{\"tag1\", \"tag2\", ...}
        :parameters {:name1 \"value1\"
                     :name2 \"value2\"
                     ...}}

   Certain attributes are treated special:

   * `:type` and `:title` are used to produce a `resource-spec` for
     this resource

   ### Edge

   A representation of an \"edge\" (dependency or containment) in the
   catalog. All edges have the following form:

       {:source       <resource spec>
        :target       <resource spec>
        :relationship <relationship id>}

   A relationship identifier can be one of:

   * `:contains`
   * `:required-by`
   * `:notifies`
   * `:before`
   * `:subscription-of`

   ### Catalog

   A wire-format-neutral representation of a Puppet catalog. It is a
   map with the following structure:

       {:certname    \"...\"
        :version     \"...\"
        :resources   {<resource-spec> <resource>
                      <resource-spec> <resource>
                      ...}
        :edges       #(<dependency-spec>,
                       <dependency-spec>,
                       ...)}"
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.set :as set]
            [puppetlabs.puppetdb.cheshire :as json]
            [digest]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.core.match :refer [match]]))

(def ^:const catalog-version
  "Constant representing the version number of the PuppetDB
  catalog format"
  5)

(pls/defn-validated collapse :- {s/Any s/Any}
  "Combines the `data` and `metadata` section of the given `catalog` into a
  single map."
  [{:keys [metadata data] :as catalog} :- {:metadata {s/Any s/Any} :data {s/Any s/Any} s/Any s/Any}]
  {:pre [(empty? (set/intersection (kitchensink/keyset metadata) (kitchensink/keyset data)))]}
  (merge metadata data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Schemas

;;; Schemas for all catalog versions, still a WIP, need to get
;;; edges/resources in here and more of the codebase switched over to
;;; using the new schemas

(def full-catalog
  "This flattened catalog schema is for the superset of catalog information.
   Use this when in the general case as it can be converted to any of the other
   (v1-v5) schemas"
  {:name s/Str
   :version s/Str
   :environment (s/maybe s/Str)
   :transaction-uuid (s/maybe s/Str)
   :producer-timestamp (s/maybe pls/Timestamp)

   ;; This is a crutch. We use sets for easier searching and avoid
   ;; reliance on ordering. We should pick one of the below (probably
   ;; set) and make the conversion earlier in the pipeline, then all
   ;; code would rely on the edges to be in that format
   :edges (s/either [{s/Any s/Any}]
                    #{{s/Any s/Any}})

   ;; This is a crutch, some areas of the code expect the first
   ;; format, others expect the second, ideally we would make the
   ;; transformation to the second earlier in the process, then all of
   ;; the code can expect it (it's just a faster access version of the first)
   :resources (s/either [{s/Any s/Any}]
                        {s/Any {s/Any s/Any}})
   :api_version (s/maybe s/Int)})

(defn old-wire-format-schema
  "Function for converting a v1-v3 schema into the wire format for that version"
  [canonical-catalog-wireformat]
  {:metadata {:api_version (:api_version canonical-catalog-wireformat)}
   :data (dissoc canonical-catalog-wireformat :api_version)})

(defn catalog-wireformat
  "Returns the correct schema for the `version`, use :all for the full-catalog (superset)"
  [version]
  (case version
    :all full-catalog
    :v5 (dissoc full-catalog :api_version)
    :v4 (dissoc full-catalog :api_version :producer-timestamp)
    :v3 (-> full-catalog
            (dissoc :environment :producer-timestamp)
            old-wire-format-schema)
    :v2 (update-in (catalog-wireformat :v3) [:data] dissoc :transaction-uuid)
    :v1 (assoc-in (catalog-wireformat :v2) [:data s/Any] s/Any)
    (catalog-wireformat :v5)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Conversion functions

(defn default-missing-keys
  "Given a catalog (any canonical version) and add any missing
   keys for it to be the full version"
  [catalog]
  (utils/assoc-when catalog
                    :transaction-uuid nil
                    :environment nil
                    :producer-timestamp nil
                    :api_version 1))

(defn collapse-if
  [catalog]
  (if (:data catalog)
    (collapse catalog)
    catalog))

(pls/defn-validated canonical-catalog
  "Converts `catalog` to `version` in the canonical format, adding
   and removing keys as needed"
  [version catalog]
  (let [target-schema (catalog-wireformat version)
        strip-keys #(pls/strip-unknown-keys target-schema %)]
    (s/validate target-schema
                (case version
                  :v1 (strip-keys
                        (old-wire-format-schema
                          (dissoc catalog :transaction-uuid :environment :producer-timestamp)))
                  :v2 (strip-keys (old-wire-format-schema (dissoc catalog :transaction-uuid
                                            :environment :producer-timestamp)))
                  :v3 (strip-keys (old-wire-format-schema (dissoc catalog :environment :producer-timestamp)))
                  :v4 (strip-keys (dissoc (collapse-if catalog) :api_version :producer-timestamp))
                  :all (strip-keys (default-missing-keys (collapse-if catalog)))
                  (strip-keys (dissoc (collapse-if catalog) :api_version))))))

(def ^:const valid-relationships
  #{:contains :required-by :notifies :before :subscription-of})

;; ## Utility functions

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

(defn transform-catalog
  "Does any munging needed for the catalog properties itself before being validated"
  [catalog]
  (update-in catalog [:version] str))

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
  "Ensure that the set of keys in the catalog is exactly the set specified in `valid-catalog-attrs`."
  [valid-catalog-attrs]
  (fn [catalog]
    {:pre [(map? catalog)]
     :post [(= % catalog)]}
    (let [present-keys (kitchensink/keyset catalog)
          extra-keys (set/difference present-keys valid-catalog-attrs)
          missing-keys (set/difference valid-catalog-attrs present-keys)]
      (when (seq extra-keys)
        catalog
        (throw (IllegalArgumentException. (format "Catalog has unexpected keys: %s" (string/join ", " (map name extra-keys))))))
      (when (seq missing-keys)
        (throw (IllegalArgumentException. (format "Catalog is missing keys: %s" (string/join ", " (map name missing-keys)))))))
    catalog))

(def validate
  "Function for validating v1->v3 of the catalogs"
  (comp validate-edges validate-resources #(s/validate (catalog-wireformat :all) %)))

;; ## High-level parsing routines


(def transform
  "Applies every transformation to the catalog, converting it from wire format
  to our internal structure."
  (comp
   transform-edges
   transform-resources))

;; ## Deserialization

(defmulti parse-catalog
  "Parse a wire-format `catalog` object or string of the specified `version`,
  returning a PuppetDB-suitable representation."
  (fn [catalog version]
    (match [catalog version]
           [(_ :guard string?) _]
           String

           [(_ :guard map?) (_ :guard number?)]
           version

           [(_ :guard map?) (_ :guard (complement number?))]
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
      (update-in [:data] dissoc :classes :tags)
      (parse-catalog (inc version))))

(defmethod parse-catalog 2
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (parse-catalog catalog (inc version)))

(defmethod parse-catalog 3
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (->> catalog
       collapse
       transform
       transform-catalog
       (canonical-catalog :all)
       validate))

(defmethod parse-catalog 4
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (->> catalog
       transform
       (canonical-catalog :all)
       validate))

(defmethod parse-catalog 5
  [catalog version]
  {:pre [(map? catalog)
         (number? version)]
   :post [(map? %)]}
  (->> catalog
       transform
       (canonical-catalog :all)
       validate))

(defmethod parse-catalog :default
  [catalog version]
  (throw (IllegalArgumentException. (format "Unknown catalog version '%s'" version))))
