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
;; 2. Containment edges may point to resources that don't exist in the
;;    catalog's list of resources
;;
;; 3. There is no pre-constructed list of dependency edges
;;
;; 4. Tags and classes are represented as lists (and may contain
;;    duplicates) instead of sets
;;
;; 5. Resources are represented as a list instead of a map, making
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
;; ### Aliases
;;
;; In a wire format catalog, edges can refer to resources either by
;; their type and either their title, or an alias (specified on the
;; resource using the _alias_ metaparameter). Internally, a CMDB
;; catalog normalizes all edges so that they use only _true_ (not
;; aliased) resources.
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
;;                     ...)
;;      :aliases     {<resource-spec> <resource-spec>}}
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
  (let [[[_ type title]] (re-seq #"(^.*?)\[(.*)\]$" str)]
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

;; ## Containment edge normalization
;;
;; The following functions are used to transform the list of
;; containment edges included in a catalog into a list of dependency
;; specifications, adding to the catalog whatever resources are
;; missing yet still pointed to by an edge.

(defn normalize-containment-edges
  "Turn containment edges in a catalog into properly split type/title
  resources with relationship specifications.

  Turns edges that look like:

    {\"source\" \"Class[foo]\" \"target\" \"User[bar]\"}

  into:

    {:source {:type \"Class\" :title \"foo\"}
     :target {:type \"User\" :title \"bar\"}
     :relationship :contains}"
  [{:keys [edges] :as catalog}]
  {:pre  [(coll? edges)
          (every? string? (mapcat keys edges))]
   :post [(every? map? (% :edges))]}
  (let [parsed-edges (for [{:strs [source target]} edges]
                       {:source (resource-spec-to-map source)
                        :target (resource-spec-to-map target)
                        :relationship :contains})]
    (assoc catalog :edges (set parsed-edges))))

(defn resource-names
  "Return a set of resource-specs for all the resources in the catalog"
  [{:keys [resources]}]
  {:pre  [resources
          (not (map? resources))]
   :post [(= (count resources) (count %))]}
  (into #{} (for [{:keys [type title]} resources]
              {:type type :title title})))

(defn edge-names
  "Return a set of resource-specs for all the edges in the catalog.

  A single edge is represented as multiple entries in the resulting
  set, with one entry for the source and one for the target."
  [{:keys [edges]}]
  {:pre  [edges]}
  (let [parsed-edges (for [{:keys [source target]} edges]
                       [source target])]
    (into #{} (apply concat parsed-edges))))

(defn add-resources-for-edges
  "Adds to the supplied catalog skeleton entries for resources
  mentioned in edges, yet not present in the resources list.

  Resources added this way are 'bare', in that they have no parameters
  or other attributes beyond 'exported', which we forcibly set to
  false."
  [{:keys [resources edges] :as catalog}]
  {; Upon return, all pre-existing resources should still be there
   :post [(let [before   (set resources)
                after    (set (% :resources))
                excluded (clojure.set/difference before after)]
            (zero? (count excluded)))]}
  (let [missing-resources (clojure.set/difference
                           (edge-names catalog)
                           (resource-names catalog))
        new-resources     (into resources
                                (for [r missing-resources]
                                  (merge r {:exported false})))]
    (assoc catalog :resources new-resources)))

;; ## Alias normalization
;;
;; Aliases are represented as a map of resource-specs to
;; resource-specs. The key is the alias, and the value is what the
;; alias points to in the catalog.

(defn alias-for-resource
  "Given a resource, return a map of its resource-spec to the
  resource-spec of its alias. If no alias parameter is present, `nil`
  is returned."
  [{:keys [type title] :as resource}]
  {:pre [(string? type)
         (string? title)]}
  (if-let [aliases (get-in resource [:parameters "alias"])]
    (let [aliases (if (coll? aliases) aliases [aliases])]
      (for [alias aliases]
        [{:type type :title alias} {:type type :title title}]))))

(defn build-alias-map
  "Returns a version of the supplied catalog augmented with an
  `:aliases` attribute that contains a map from an alias (represented
  as a resource-spec) to the alias' target (also represented as a
  resource-spec)."
  [{:keys [resources] :as catalog}]
  {:pre [(map? resources)]}
  (let [aliases (->> resources
                     (vals)
                     (mapcat alias-for-resource)
                     (remove nil?)
                     (into {}))]
    (assoc catalog :aliases aliases)))

(defn resolve-aliases-in-edges
  "Return a copy of the supplied list of edges, where each source and
  target in each edge goes through alias resolution. If the edge doesn't
  refer to any aliases, it is returned unchanged."
  [edges aliases]
  {:pre  [(coll? edges)
          (map? aliases)]
   :post [(= (count edges) (count %))]}
  (let [resolve (fn [r] (get aliases r r))]
    (for [{:keys [source target relationship]} edges]
      {:source (resolve source)
       :target (resolve target)
       :relationship relationship})))

(defn normalize-aliases
  "Using the alias-map and edges from a catalog, replace any
  references to aliases in any edges with the resources they point
  to."
  [{:keys [aliases edges] :as catalog}]
  (assoc catalog :edges (into #{} (resolve-aliases-in-edges edges aliases))))

;; ## Dependency edge normalization
;;
;; The following functions will handle walking the catalog's list of
;; resources and compiling a set of dependency specifications that
;; model the relationships between resources.
;;
;; All edges are modeled as having a source and a target, so the
;; question is how do we decide what's the source and what's the
;; target for a given relationship? For now, we've decided on a
;; convention: the source should be something that's temporally
;; ordered before the target, in terms of Puppet evaluation. This
;; mirrors the `Source -> Target` arrow convention in the Puppet DSL,
;; as that implies a temporal ordering.

(def relationship-mapping-table
  ^{:doc "Translates between relationship specifiers in a wire-format
  catalog to the set of attributes necessary to produce a dependency
  specification"}
  {"subscribe" {:direction :reverse :relationship :subscription-of}
   "notify"    {:direction :forward :relationship :notifies}
   "before"    {:direction :forward :relationship :before}
   "require"   {:direction :reverse :relationship :required-by}})

(defn build-dependencies-for-resource
  "Given a resource, return a lazy seq of dependency specifications
  applicable to that resource.

  This will include relationships extracted from dependency-signifying
  resource attributes like subscribe, require, et all."
  [{:keys [type title parameters] :as resource :or {parameters {}}}]
  {;; type and title cannot be nil
   :pre [type
         title]}
  (let [resource-spec     {:type type :title title}
        emit-dependency   (fn [child relationship]
                            (let [direction (get-in relationship-mapping-table [relationship :direction])
                                  rel       (get-in relationship-mapping-table [relationship :relationship])]
                              (if (= direction :forward)
                                {:source resource-spec :target child :relationship rel}
                                {:source child :target resource-spec :relationship rel})))]

    (flatten
     ; Examine the resource's value for each order-specifying parameter
     (for [relationship (keys relationship-mapping-table)
           :let [param-value (parameters relationship)]
           :when param-value]
       (let [children (->> (pl-utils/as-collection param-value)
                           (map resource-spec-to-map))
             dependencies (map #(emit-dependency % relationship) children)]
         dependencies)))))

(defn build-dependency-edges
  "Using the dependency and containment information from a catalog,
  build a dependency graph for all the resources."
  [{:keys [resources edges] :as catalog}]
  {:pre [(map? resources)
         (coll? edges)]
   :post [(>= (count (% :edges)) (count edges))]}
  (let [new-edges (->> (vals resources)
                       (mapcat build-dependencies-for-resource)
                       (concat edges)
                       (set))]
    (assoc catalog :edges new-edges)))

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
  "Ensure that all edges have valid sources and targets"
  [{:keys [edges resources] :as catalog}]
  {:pre [(set? edges)
         (map? resources)]}
  (doseq [{:keys [source target] :as edge} edges
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
      (normalize-containment-edges)
      (add-resources-for-edges)
      (mapify-resources)
      (build-alias-map)
      (build-dependency-edges)
      (normalize-aliases)
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
