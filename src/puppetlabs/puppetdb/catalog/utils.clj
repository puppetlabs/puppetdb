(ns puppetlabs.puppetdb.catalog.utils
  "Catalog generation and manipulation

   A suite of functions that aid in constructing random catalogs, or
   randomly modifying an existing catalog (wire format or parsed)."
  (:require [puppetlabs.puppetdb.catalogs :as cat]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.random :refer [random-resource random-kw-resource
                                                random-parameters]]))

(defn convert-to-wire
  "Converts a catalog in the internal format to the wire format"
  [catalog]
  (-> catalog
      (update-in [:resources] vals)
      (update-in [:edges] (fn [edges]
                            (map #(update-in % [:relationship] name) edges)))))

(defn convert-internal-catalog-fn
  "Takes a function that transforms a catalog in the internal format
   and returns a function that converts from wire to internal, applies `f`, then
   converts the catalog back to a the wire format"
  [f]
  (fn [wire-catalog]
    (-> wire-catalog
        walk/keywordize-keys
        (cat/parse-catalog 6)
        f
        convert-to-wire)))

(defn add-random-resource-to-catalog
  "Adds a random resource to the given catalog"
  [{:keys [resources] :as c}]
  (let [new-resource (random-kw-resource)
        key          {:type (:type new-resource) :title (:title new-resource)}]
    (assoc c :resources (assoc resources key (random-kw-resource)))))

(def add-random-resource-to-wire-catalog
  "Adds a random resource to the given wire-format catalog"
  (convert-internal-catalog-fn add-random-resource-to-catalog))

(defn mod-resource-in-catalog
  "Modifies a randomly chosen resource in the given catalog.

  Takes the candidate resources, and gives it a new, randomly
  generated set of parameters"
  [{:keys [resources] :as c}]
  (let [k            (rand-nth (keys resources))
        r            (resources k)
        new-resource (assoc r :parameters (random-parameters))]
    (assoc c :resources (assoc resources k new-resource))))

(def mod-resource-in-wire-catalog
  "Modifies a randomly chosen resource in the given
   wire-format catalog."
  (convert-internal-catalog-fn mod-resource-in-catalog))

(defn mod-resource-metadata-in-catalog
  "Modifies the metadata of a randomly chosen resource in the given catalog.

  Generates a random resource and applies the old parameters to it."
  [{:keys [resources] :as c}]
  (let [k            (rand-nth (keys resources))
        r            (resources k)
        new-resource (merge (random-kw-resource) {:type (:type r) :title (:title r) :parameters (:parameters r)})]
    (assoc c :resources (assoc resources k new-resource))))

(def relationships
  "Set of all possible edge relationships"
  #{:contains :before :required-by :notifies :subscription-of})

(defn rand-relationship
  "Picks a edge relationship at random from `relationships`. If a
   `prev-relationship` is provided, it ensures it doesn't randomly
   pick that relationship."
  ([] (rand-relationship nil))
  ([prev-relationship]
     (rand-nth (seq (disj relationships prev-relationship)))))

(defn add-random-edge-to-catalog
  "Creates a new edge between 2 randomly chosen resources, and adds it
  to the catalog"
  [{:keys [edges resources] :as c}]
  (let [make-edge (fn []
                    (let [source (rand-nth (vals resources))
                          target (rand-nth (vals resources))]
                      {:source {:type (:type source) :title (:title source)}
                       :target {:type (:type target) :title (:title target)}
                       :relationship (rand-relationship)}))
        ;; Generate at most 100 edges
        new-edge  (first (remove edges (repeatedly 100 make-edge)))]
    (if new-edge
      (assoc c :edges (conj edges new-edge))
      c)))

(def add-random-edge-to-wire-catalog
  "Adds a random resource to the given wire-format catalog"
  (convert-internal-catalog-fn add-random-edge-to-catalog))

(defn swap-edge-targets-in-catalog
  "Picks 2 random edges in the given catalog, and swaps their targets
  around"
  [{:keys [edges] :as c}]
  {:post [(or (not (seq edges))
              (<= 2 (count edges))
              (not= edges %))]}
  (let [edge1     (rand-nth (seq edges))
        edge2     (rand-nth (seq (disj edges edge1)))
        new-edges (-> edges
                      (disj edge1)
                      (disj edge2)
                      (conj (assoc edge1
                              :target (:target edge2)
                              :relationship (rand-relationship (:relationship edge1))))
                      (conj (assoc edge2
                              :target (:target edge1)
                              :relationship (rand-relationship (:relationship edge2)))))]
    (assoc c :edges new-edges)))

(def swap-edge-targets-in-wire-catalog
  (convert-internal-catalog-fn swap-edge-targets-in-catalog))
