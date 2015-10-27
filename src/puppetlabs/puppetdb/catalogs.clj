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
  (:require [clojure.core.match :refer [match]]
            [clojure.set :as set]
            [clojure.string :as string]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]))

(def ^:const catalog-version
  "Constant representing the version number of the PuppetDB
  catalog format"
  7)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Schemas

;;; Schemas for all catalog versions, still a WIP, need to get
;;; edges/resources in here and more of the codebase switched over to
;;; using the new schemas

(def catalog-wireformat-schema
  "This flattened catalog schema is for the superset of catalog information.
   Use this when in the general case as it can be converted to any of the other
   (v5-) schemas"
  {:certname s/Str
   :version s/Str
   :environment (s/maybe s/Str)
   :transaction_uuid (s/maybe s/Str)
   :producer_timestamp pls/Timestamp
   :code_id (s/maybe s/Str)


   ;; This is a crutch. We use sets for easier searching and avoid
   ;; reliance on ordering. We should pick one of the below (probably
   ;; set) and make the conversion earlier in the pipeline, then all
   ;; code would rely on the edges to be in that format
   :edges (s/cond-pre [{s/Any s/Any}]
                      #{{s/Any s/Any}})

   ;; This is a crutch, some areas of the code expect the first
   ;; format, others expect the second, ideally we would make the
   ;; transformation to the second earlier in the process, then all of
   ;; the code can expect it (it's just a faster access version of the first)
   :resources (s/cond-pre [{s/Any s/Any}]
                          {s/Any {s/Any s/Any}})})

(def catalog-v6-wireformat-schema
  (dissoc catalog-wireformat-schema :code_id))

(def edge-query-schema
  "Schema for validating a single edge."
  {(s/optional-key :certname) s/Str
   :relationship s/Str
   :source_title s/Str
   :source_type s/Str
   :target_title s/Str
   :target_type s/Str})

(def edges-expanded-query-schema
  "Edges expanded format schema."
  {(s/optional-key :data) [edge-query-schema]
   :href s/Str})

(def resource-query-schema
  "Schema for validating a single resource."
  {(s/optional-key :certname) s/Str
   (s/optional-key :environment) s/Str
   :exported s/Bool
   :file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :parameters {s/Any s/Any}
   :resource s/Str
   :tags [(s/maybe s/Str)]
   :title s/Str
   :type s/Str})

(def resources-expanded-query-schema
  "Resources expanded format schema."
  {:href s/Str
   (s/optional-key :data) [resource-query-schema]})

(def catalog-query-schema
  "Full catalog query result schema."
  {(s/optional-key :certname) s/Str
   (s/optional-key :edges) edges-expanded-query-schema
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :hash) s/Str
   (s/optional-key :producer_timestamp) (s/maybe pls/Timestamp)
   (s/optional-key :resources) resources-expanded-query-schema
   (s/optional-key :transaction_uuid) (s/maybe s/Str)
   (s/optional-key :code_id) (s/maybe s/Str)
   (s/optional-key :version) s/Str})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Conversion functions

(defn default-missing-keys
  "Given a catalog (any canonical version) and add any missing
   keys for it to be the full version"
  [catalog]
  (utils/assoc-when catalog
                    :transaction_uuid nil
                    :environment nil))

(defn wire-v6->wire-v7 [catalog]
  (assoc catalog :code_id nil))

(defn wire-v5->wire-v7 [catalog]
  (-> catalog
      (set/rename-keys {:name :certname})
      utils/dash->underscore-keys
      (dissoc :api_version)
      wire-v6->wire-v7))

(defn wire-v4->wire-v7 [catalog received-time]
  (-> catalog
      (assoc :producer_timestamp received-time)
      wire-v5->wire-v7))

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
        (throw (IllegalArgumentException. (format "Catalog has unexpected keys: %s" (string/join ", " (map name extra-keys))))))
      (when (seq missing-keys)
        (throw (IllegalArgumentException. (format "Catalog is missing keys: %s" (string/join ", " (map name missing-keys)))))))
    catalog))

(defn validate
  "Function for validating v7- of the catalogs"
  [catalog]
  (->> catalog
       (s/validate catalog-wireformat-schema)
       validate-resources
       validate-edges))

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
  (fn [catalog version received-time]
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
  [catalog version received-time]
  {:pre   [(string? catalog)]
   :post  [(map? %)]}
  (parse-catalog (json/parse-string catalog true) version received-time))

(defmethod parse-catalog 4
  [catalog version received-time]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v4->wire-v7 catalog received-time)
                 7 nil))

(defmethod parse-catalog 5
  [catalog version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v5->wire-v7 catalog)
                 7 nil))

(defmethod parse-catalog 6
  [catalog version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v6->wire-v7 catalog)
                7 nil))

(defmethod parse-catalog 7
  [catalog version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (->> catalog
       default-missing-keys
       transform
       (pls/strip-unknown-keys catalog-wireformat-schema)
       validate))

(defmethod parse-catalog :default
  [catalog version _]
  (throw (IllegalArgumentException. (format "Unknown catalog version '%s'" version))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Query -> Wire format conversions

(pls/defn-validated edge-query->wire-v7
  [edge :- edge-query-schema]
  {:source
   {:title (:source_title edge)
    :type (:source_type edge)}
   :target
   {:title (:target_title edge)
    :type (:target_type edge)}
   :relationship (:relationship edge)})

(pls/defn-validated edges-expanded->wire-v7
  [edges :- edges-expanded-query-schema]
  (map edge-query->wire-v7
       (:data edges)))

(pls/defn-validated resource-query->wire-v7
  [resource :- resource-query-schema]
  (-> resource
      (dissoc :resource :certname :environment)
      (kitchensink/dissoc-if-nil :file :line)))

(pls/defn-validated resources-expanded->wire-v7
  [resources :- resources-expanded-query-schema]
  (map resource-query->wire-v7
       (:data resources)))

(pls/defn-validated catalog-query->wire-v7 :- catalog-wireformat-schema
  [catalog :- catalog-query-schema]
  (-> catalog
      (dissoc :hash)
      (update :edges edges-expanded->wire-v7)
      (update :resources resources-expanded->wire-v7)))

(defn catalogs-query->wire-v7 [catalogs]
  (map catalog-query->wire-v7 catalogs))
