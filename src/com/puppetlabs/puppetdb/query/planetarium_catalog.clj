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
(ns com.puppetlabs.puppetdb.query.planetarium-catalog
  (:refer-clojure :exclude  [case compile conj! distinct disj! drop sort take])
  (:require [com.puppetlabs.puppetdb.query.resources :as r])
  (:use [com.puppetlabs.jdbc]
        [com.puppetlabs.puppetdb.scf.storage :only [catalog-hash-for-certname]]
        [com.puppetlabs.puppetdb.query.catalogs :only [get-edges]]
        clojureql.core))

(defn catalog-for-node
  "Retrieve the catalog for `node`."
  [node]
  {:pre  [(string? node)]
   :post [(or (nil? %)
              (and (map? %)
                   (= node (:name %))
                   (map? (:resources %))
                   (set? (:edges %))))]}
  (when (catalog-hash-for-certname node)
    (let [resources       (-> ["=" "certname" node]
                            (r/v2-query->sql)
                            (r/query-resources)
                            (:result))
          resource-counts (if (seq resources)
                            @(-> (table :catalog_resources)
                                 (select (where (in :resource (map :resource resources))))
                                 (aggregate [[:count/* :as :copies]] [:type :title]))
                            [])
          resource-counts (into {} (for [{:keys [type title copies]} resource-counts]
                                     [{:type type :title title} copies]))
          resource-map    (into {} (for [{:keys [type title] :as resource} resources]
                                     [(format "%s[%s]" type title) (assoc resource :count (resource-counts {:type type :title title}))]))
          edges           (set (get-edges node))]
      {:name      node
       :resources resource-map
       :edges     edges})))
