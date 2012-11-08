;; ## Catalog retrieval
;;
;; A catalog will be returned in the form:
;;
;;     {:name      "foo.example.com"
;;      :resources {<resource-ref> <resource>
;;                  <resource-ref> <resource>
;;                  ...}
;;      :edges     #{<dependency-spec>
;;                   <dependency-spec>}}
(ns com.puppetlabs.puppetdb.query.catalog
  (:refer-clojure :exclude  [case compile conj! distinct disj! drop sort take])
  (:require [com.puppetlabs.puppetdb.query.resource :as r])
  (:use [com.puppetlabs.jdbc]
        [com.puppetlabs.puppetdb.scf.storage :only [catalogs-for-certname]]
        clojureql.core))

(defn get-edges
  "Fetch the edges for the current catalog for the given `node`."
  [node]
  (let [query (str "SELECT sources.type AS source_type, sources.title AS source_title, targets.type AS target_type, targets.title AS target_title, edges.type AS relationship "
                   "FROM certname_catalogs INNER JOIN edges USING(catalog) INNER JOIN catalog_resources sources ON edges.catalog = sources.catalog AND source = sources.resource "
                   "INNER JOIN catalog_resources targets ON edges.catalog = targets.catalog AND target = targets.resource WHERE certname = ?")]
    (set (for [{:keys [source_type source_title target_type target_title relationship]} (query-to-vec query node)]
           {:source       {:type source_type :title source_title}
            :target       {:type target_type :title target_title}
            :relationship relationship}))))

(defn catalog-for-node
  "Retrieve the catalog for `node`."
  [node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (and (map? %)
                   (= node (:name %))
                   (map? (:resources %))
                   (set? (:edges %))))]}
  (when (seq (catalogs-for-certname node))
    (let [resources       (r/query-resources (r/v2-query->sql ["=" "certname" node]))
          resource-counts (if (seq resources)
                            @(-> (table :catalog_resources)
                                 (select (where (in :resource (map :resource resources))))
                                 (aggregate [[:count/* :as :copies]] [:type :title]))
                            [])
          resource-counts (into {} (for [{:keys [type title copies]} resource-counts]
                                     [{:type type :title title} copies]))
          resource-map    (into {} (for [{:keys [type title] :as resource} resources]
                                     [(format "%s[%s]" type title) (assoc resource :count (resource-counts {:type type :title title}))]))
          edges           (get-edges node)]
      {:name      node
       :resources resource-map
       :edges     edges})))
