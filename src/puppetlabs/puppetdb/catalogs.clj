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
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.utils.string-formatter :as formatter]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]]))

(def ^:const catalog-version
  "Constant representing the version number of the PuppetDB
  catalog format"
  8)

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
   :catalog_uuid (s/maybe s/Str)
   :producer_timestamp pls/Timestamp
   :producer (s/maybe s/Str)
   :code_id (s/maybe s/Str)
   (s/optional-key :job_id) (s/maybe s/Str)


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

(def catalog-v8-wireformat-schema
  (dissoc catalog-wireformat-schema :producer :job_id))

(def catalog-v7-wireformat-schema
  (dissoc catalog-v8-wireformat-schema :catalog_uuid))

(def catalog-v6-wireformat-schema
  (dissoc catalog-v7-wireformat-schema :code_id))

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
   (s/optional-key :producer) (s/maybe s/Str)
   (s/optional-key :resources) resources-expanded-query-schema
   (s/optional-key :transaction_uuid) (s/maybe s/Str)
   (s/optional-key :catalog_uuid) (s/maybe s/Str)
   (s/optional-key :code_id) (s/maybe s/Str)
   (s/optional-key :job_id) (s/maybe s/Str)
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

(defn wire-v8->wire-v9 [catalog]
  (assoc catalog :producer nil :job_id nil))

(defn wire-v7->wire-v9 [{:keys [transaction_uuid] :as catalog}]
  (-> catalog
      (assoc :catalog_uuid transaction_uuid)
      wire-v8->wire-v9))

(defn wire-v6->wire-v9 [catalog]
  (-> catalog
      (assoc :code_id nil)
      wire-v7->wire-v9))

(defn wire-v5->wire-v9 [catalog]
  (-> catalog
      (set/rename-keys {:name :certname})
      formatter/dash->underscore-keys
      (dissoc :api_version)
      wire-v6->wire-v9))

(defn wire-v4->wire-v9 [catalog received-time]
  (-> catalog
      (assoc :producer_timestamp received-time)
      wire-v5->wire-v9))

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
  (update edge :relationship keyword))

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

;; the kind field was added to improve agent/server communication, so resources
;; are expected to have it, but we do not store it. Other unrecognized keys will
;; be added to it so that each unrecognized key is only logged once per startup.
(def already-seen-unrecognized-keys (atom #{:kind}))

(defn- strip-unknown-attributes
  "Removes unknown attributes from a resource. This is essential for the forward compatibility
  of PuppetDB when the puppet agent makes additions to its resource definition."
  [{:keys [type title] :as resource} expected-keys already-seen-unrecognized-keys]
  (let [unrecognized-keys (set/difference (set (keys resource))
                                          expected-keys
                                          @already-seen-unrecognized-keys)]
    (when-let [ks (seq unrecognized-keys)]
      (log/warn (trs "Ignoring unrecognized catalog resource key(s) {0}. Future warnings will be surpressed."
                     ks type title))
      (swap! already-seen-unrecognized-keys set/union unrecognized-keys))
    (select-keys resource expected-keys)))

(defn transform-resource*
  "Normalizes the structure of a single `resource`."
  [resource expected-keys already-seen-unrecognized-keys]
  {:pre [(map? resource)]
   :post [(set? (:tags %))]}
  (-> resource
      transform-tags
      (strip-unknown-attributes expected-keys already-seen-unrecognized-keys)))

(defn transform-resources
  "Turns the list of resources into a mapping of
  `{resource-spec resource, ...}`, as well as transforming each resource."
  [{:keys [resources] :as catalog}]
  {:pre  [(coll? resources)
          (not (map? resources))]
   :post [(map? (:resources %))
          (= (count resources) (count (:resources %)))]}
  (let [already-seen-unrecognized-keys already-seen-unrecognized-keys
        expected-keys #{:type :title :exported :file :line :tags :aliases :parameters}
        new-resources (into {} (for [{:keys [type title] :as resource} resources
                                     :let [resource-spec    {:type type :title title}
                                           new-resource     (transform-resource* resource
                                                                                 expected-keys
                                                                                 already-seen-unrecognized-keys)]]
                                 [resource-spec new-resource]))]
    (assoc catalog :resources new-resources)))

;; ## Integrity checking
;;
;; Functions to ensure that the catalog structure is coherent.

(def ^:const tag-pattern
  ; tags are case insensitive, and Puppet automatically converts
  ; tags to lower case, so we do not consider uppercase letters in
  ; our valid tag regex.
  ; See the doc for more info https://puppet.com/docs/puppet/6.4/lang_reserved.html#tags
  #"[[\p{L}&&[^\p{Lu}]]\p{N}_][[\p{L}&&[^\p{Lu}]]\p{N}_:.-]*")

(defn validate-resources
  "Ensure that all resource tags conform to the allowed tag pattern."
  [{:keys [resources] :as catalog}]
  {:pre [(map? resources)]
   :post [(= % catalog)]}
  (doseq [[resource-spec resource] resources]
    (when-let [invalid-tag (first
                            (remove #(re-matches tag-pattern %) (:tags resource)))]
      (throw (IllegalArgumentException.
              (str
               (trs "Resource ''{0}'' has an invalid tag ''{1}''." resource-spec invalid-tag)
               " "
               (trs "Tags must match the pattern /{0}/." tag-pattern))))))
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
              (trs "Edge ''{0}'' refers to resource ''{1}'', which doesn't exist in the catalog." edge resource))))
    (when-not (valid-relationships relationship)
      (throw (IllegalArgumentException.
              (trs "Edge ''{0}'' has invalid relationship type ''{1}''" edge relationship)))))
  catalog)

(defn validate
  "Function for validating v9- of the catalogs"
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
  (fn [catalog version _received-time]
    (match [catalog version]
           [(_ :guard string?) _]
           String

           [(_ :guard map?) (_ :guard number?)]
           version

           [(_ :guard map?) (_ :guard (complement number?))]
           (throw (IllegalArgumentException.
                   (trs "Catalog version ''{0}'' is not a legal version number" version)))

           ;; At this point, catalog can't be a string or a map (regardless of
           ;; what version is), so there's our problem!
           :else
           (throw (IllegalArgumentException.
                   (trs "Catalog must be specified as a string or a map, not ''{0}''" (class catalog)))))))

(defmethod parse-catalog String
  [catalog version received-time]
  {:pre   [(string? catalog)]
   :post  [(map? %)]}
  (parse-catalog (json/parse-string catalog true) version received-time))

(defmethod parse-catalog 4
  [catalog _version received-time]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v4->wire-v9 catalog received-time)
                 9 nil))

(defmethod parse-catalog 5
  [catalog _version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v5->wire-v9 catalog)
                 9 nil))

(defmethod parse-catalog 6
  [catalog _version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v6->wire-v9 catalog)
                9 nil))

(defmethod parse-catalog 7
  [catalog _version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v7->wire-v9 catalog)
                 9 nil))

(defmethod parse-catalog 8
  [catalog _version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (parse-catalog (wire-v8->wire-v9 catalog)
                 9 nil))

(defmethod parse-catalog 9
  [catalog _version _]
  {:pre [(map? catalog)]
   :post [(map? %)]}
  (->> catalog
       default-missing-keys
       transform
       (pls/strip-unknown-keys catalog-wireformat-schema)
       validate))

(defmethod parse-catalog :default
  [_catalog version _]
  (throw (IllegalArgumentException.
          (trs "Unknown catalog version ''{0}''" version))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Catalog Query -> Wire format conversions

(pls/defn-validated edge-query->wire-v9
  [edge :- edge-query-schema]
  {:source
   {:title (:source_title edge)
    :type (:source_type edge)}
   :target
   {:title (:target_title edge)
    :type (:target_type edge)}
   :relationship (:relationship edge)})

(pls/defn-validated edges-expanded->wire-v9
  [edges :- edges-expanded-query-schema]
  (map edge-query->wire-v9
       (:data edges)))

(pls/defn-validated resource-query->wire-v9
  [resource :- resource-query-schema]
  (-> resource
      (dissoc :resource :certname :environment)
      (kitchensink/dissoc-if-nil :file :line)))

(pls/defn-validated resources-expanded->wire-v9
  [resources :- resources-expanded-query-schema]
  (map resource-query->wire-v9
       (:data resources)))

(pls/defn-validated catalog-query->wire-v9 :- catalog-wireformat-schema
  [catalog :- catalog-query-schema]
  (-> catalog
      (dissoc :hash)
      (update :edges edges-expanded->wire-v9)
      (update :resources resources-expanded->wire-v9)))

(defn catalogs-query->wire-v9 [catalogs]
  (map catalog-query->wire-v9 catalogs))
