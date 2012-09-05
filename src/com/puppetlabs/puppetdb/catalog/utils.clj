;; ## Catalog generation and manipulation
;;
;; A suite of functions that aid in constructing random catalogs, or
;; randomly modifying an existing catalog (wire format or parsed).

(ns com.puppetlabs.puppetdb.catalog.utils
  (:require [clojure.string :as string]
            [com.puppetlabs.puppetdb.catalog :as cat])
  (:use [clojure.walk :only [keywordize-keys]]))

(defn random-string
  "Generate a random string of optional length"
  ([] (random-string (inc (rand-int 10))))
  ([length]
     (let [ascii-codes (concat (range 48 58) (range 66 91) (range 97 123))]
       (apply str (repeatedly length #(char (rand-nth ascii-codes)))))))

(defn random-bool [] (rand-nth [true false]))

(defn random-parameters
  "Generate a random set of parameters."
  []
  (into {} (repeatedly (inc (rand-int 10)) #(vector (random-string) (random-string)))))

(defn random-resource
  "Generate a random resource. Can optionally specify type/title, as
  well as any attribute overrides.

  Note that is _parameters_ is given as an override, the supplied
  parameters are merged in with the randomly generated set."
  ([] (random-resource (random-string) (random-string)))
  ([type title] (random-resource type title {}))
  ([type title overrides]
     (let [extra-params (overrides "parameters")
           overrides    (dissoc overrides "parameters")
           r            {"type"       type
                         "title"      title
                         "exported"   (random-bool)
                         "file"       (random-string)
                         "line"       (rand-int 1000)
                         "tags"       (set (repeatedly (inc (rand-int 10)) #(string/lower-case (random-string))))
                         "parameters" (merge (random-parameters) extra-params)}]
       (merge r overrides))))

;; A version of random-resource that returns resources with keyword
;; keys instead of strings
(def random-kw-resource (comp keywordize-keys random-resource))

(defn add-random-resource-to-wire-catalog
  "Adds a random resource to the given wire-format catalog"
  [catalog]
  (let [resources (get-in catalog ["data" "resources"])
        new-rsrc  (random-resource)]
    (assoc-in catalog ["data" "resources"] (conj resources new-rsrc))))

(defn add-random-resource-to-catalog
  "Adds a random resource to the given catalog"
  [{:keys [resources] :as c}]
  (let [new-resource (random-kw-resource)
        key          {:type (:type new-resource) :title (:title new-resource)}]
    (assoc c :resources (assoc resources key (random-kw-resource)))))

(defn mod-resource-in-catalog
  "Modifies a randomly chosen resource in the given catalog.

  Takes the candidate resources, and gives it a new, randomly
  generated set of parameters"
  [{:keys [resources] :as c}]
  (let [k            (rand-nth (keys resources))
        r            (resources k)
        new-resource (assoc r :parameters (random-parameters))]
    (assoc c :resources (assoc resources k new-resource))))

(defn mod-resource-metadata-in-catalog
  "Modifies the metadata of a randomly chosen resource in the given catalog.

  Generates a random resource and applies the old parameters to it."
  [{:keys [resources] :as c}]
  (let [k            (rand-nth (keys resources))
        r            (resources k)
        new-resource (merge (random-kw-resource) {:type (:type r) :title (:title r) :parameters (:parameters r)})]
    (assoc c :resources (assoc resources k new-resource))))

(defn add-random-edge-to-catalog
  "Creates a new edge between 2 randomly chosen resources, and adds it
  to the catalog"
  [{:keys [edges resources] :as c}]
  (let [make-edge (fn []
                    (let [source (rand-nth (vals resources))
                          target (rand-nth (vals resources))]
                      {:source {:type (:type source) :title (:title source)}
                       :target {:type (:type target) :title (:title target)}}))
        ;; Generate at most 100 edges
        new-edge  (first (remove edges (take 100 (repeatedly make-edge))))]
    (if new-edge
      (assoc c :edges (conj edges new-edge))
      c)))

(defn swap-edge-targets-in-catalog
  "Picks 2 random edges in the given catalog, and swaps their targets
  around"
  [{:keys [edges] :as c}]
  (let [edge1     (rand-nth (seq edges))
        edge2     (rand-nth (seq edges))
        new-edges (-> edges
                      (disj edge1)
                      (disj edge2)
                      (conj (assoc edge1 :target (:target edge2)))
                      (conj (assoc edge2 :target (:target edge1))))]
    (assoc c :edges new-edges)))
