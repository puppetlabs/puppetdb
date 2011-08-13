(ns com.puppetlabs.cmdb.catalog
  (:require [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.contrib.duck-streams :as ds]
            [digest]
            [com.puppetlabs.utils :as pl-utils]))

(defn resource-names
  "Return a map of type and title for each resource in the catalog"
  [{:strs [resources]}]
  (into #{} (for [{:strs [type title]} resources]
              {"type" type "title" title})))

(defn edge-names
  "Return a mapping of type and title for each edge in the catalog"
  [{:strs [edges]}]
  (let [parsed-edges (for [{:strs [source target]} edges]
                       [source target])]
    (into #{} (apply concat parsed-edges))))

(defn normalize-edges
  "Turn vanilla edges in a catalog into properly split type/title resources.

Turns {'source' 'Class[foo]' 'target' 'User[bar]'}
into {'source' {'type' 'Class' 'title' 'foo'} 'target' {'type' 'User' 'title' 'bar'}}"
  [{:strs [edges] :as catalog}]
  (let [parse-edge   (fn [s]
                       (let [[[_ type title]] (re-seq #"(^.*)\[(.*)\]$" s)]
                         {"type" type "title" title}))
        parsed-edges (for [{:strs [source target]} edges]
                       {"source" (parse-edge source)
                        "target" (parse-edge target)})]
    (assoc catalog "edges" parsed-edges)))

(defn add-resources-for-edges
  "Adds to the supplied catalog skeleton entries for resources
mentioned in edges, yet not present in the resources list"
  [{:strs [resources edges] :as catalog}]
  (let [missing-resources (pl-utils/symmetric-difference (edge-names catalog) (resource-names catalog))
        new-resources     (into resources (for [r missing-resources]
                                            (merge r {"exported" false})))]
    (assoc catalog "resources" new-resources)))

(defn add-host-attribute
  "Adds a host attribute to the catalog that contains the catalog's associated hostname"
  [catalog]
  (assoc catalog "host" (catalog "name")))

(defn add-resource-hashes
  "Adds a hash to each resource that will be used as its unique identifier"
  [{:strs [resources] :as catalog}]
  (let [add-hash (fn [resource]
                   (let [hash (digest/sha-1 (json/generate-string resource))]
                     (assoc resource "hash" hash)))
        new-resources (map add-hash resources)]
    (assoc catalog "resources" new-resources)))

(defn mapify-resources
  "Turns the list of resources into a mapping of a resource's {type, title} to the resource itself"
  [{:strs [resources] :as catalog}]
  (let [new-resources (into {} (for [{:strs [type title] :as resource} resources]
                                 [{"type" type "title" title} resource]))]
    (assoc catalog "resources" new-resources)))

(defn mapify-edges
  "Turns the list of edges into a mapping of source to a set of targets for that host"
  [{:strs [edges] :as catalog}]
  (let [merge-fn (fn [m {:strs [source target]}]
                   ;; I kind of hate this code...it looks up the
                   ;; current set of targets for a given source, and
                   ;; defaults it to an empty set. Then we add the
                   ;; current target to the set. I do this kind of
                   ;; "update with default" operation often enough I
                   ;; should really just write a utility fn for it.
                   (let [targets (get m source #{})]
                     (assoc m source (conj targets target))))
        new-edges (reduce merge-fn {} edges)]
    (assoc catalog "edges" new-edges)))

(defn setify-tags-and-classes
  "Turns the catalog's list of tags and classes into proper sets"
  [{:strs [classes tags] :as catalog}]
  (assoc catalog
    "classes" (set classes)
    "tags" (set tags)))

(defn parse-from-json
  "Parse a JSON catalog located at 'filename', returning a map

This func will actually only return the 'data' key of the catalog,
which seems to contain the useful stuff."
  [filename]
  (try
    (-> (json/parse-string (slurp filename))
        (get "data")
        (add-host-attribute)
        (normalize-edges)
        (add-resources-for-edges)
        (add-resource-hashes)
        (mapify-resources)
        (mapify-edges)
        (setify-tags-and-classes))
    (catch org.codehaus.jackson.JsonParseException e
      (log/error (format "Error parsing %s: %s" filename (.getMessage e))))))

