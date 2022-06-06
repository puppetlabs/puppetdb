(ns puppetlabs.puppetdb.catalog.utils-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.catalog.utils
    :refer [add-random-edge-to-catalog
            add-random-edge-to-wire-catalog
            add-random-resource-to-catalog
            add-random-resource-to-wire-catalog
            convert-internal-catalog-fn
            mod-resource-in-catalog
            mod-resource-in-wire-catalog
            swap-edge-targets-in-catalog
            swap-edge-targets-in-wire-catalog]]
   [puppetlabs.puppetdb.cheshire :as json]
   [clojure.java.io :as io]
   [puppetlabs.puppetdb.examples :as ex]
   [puppetlabs.kitchensink.core :as kitchensink]
   [clojure.set :as set]))

(defn convert-tags
  "Tags from JSON parse as a list, change that to a set for
   easier comparison."
  [resource]
  (update-in resource [:tags] set))

(def wire-catalog
  "Basic test wire-format catalog"
  (json/parse-string (slurp (io/resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json")) true))

(defn wire-resources
  "Returns the resources from a wire-format catalog"
  [wire-catalog]
  (:resources wire-catalog))

(defn wire-edges
  "Returns the edges from a wire-format catalog"
  [wire-catalog]
  (get wire-catalog :edges))

(deftest test-wire-catalog-conversion-fn
  (let [{:keys [edges resources]} wire-catalog
        result ((convert-internal-catalog-fn identity) wire-catalog)]

    (is (= (set (map convert-tags resources))
           (set (wire-resources result))))

    (is (= (set edges)
           (set (wire-edges result))))
    (is (= (get-in wire-catalog [:name])
           (get-in result [:name])))))

(deftest test-add-random-resource-to-catalog
  (let [catalog (:basic ex/catalogs)]
    (is (= (inc (count (:resources catalog)))
           (count (:resources (add-random-resource-to-catalog catalog)))))))

(deftest test-add-random-resource-to-wire-catalog
  (let [result (add-random-resource-to-wire-catalog wire-catalog)]
    (is (= (inc (count (wire-resources wire-catalog)))
           (count (wire-resources result))))

    (is (= (get-in wire-catalog [:name])
           (get-in result [:name])))))

(deftest test-add-random-edge-to-catalog
  (let [catalog (:basic ex/catalogs)]
    (is (= (inc (count (:edges catalog)))
           (count (:edges (add-random-edge-to-catalog catalog)))))))

(deftest test-add-random-edge-to-wire-catalog
  (is (= (inc (count (wire-edges wire-catalog)))
         (count (wire-edges (add-random-edge-to-wire-catalog wire-catalog))))))

(deftest test-mod-resource-in-catalog
  (let [catalog (:basic ex/catalogs)
        old-resources (kitchensink/valset (:resources catalog))
        result (mod-resource-in-catalog catalog)
        new-resources (kitchensink/valset (:resources result))]
    (is (= 3 (count (:resources result))))
    (is (= 2 (count (set/intersection old-resources new-resources))))))

(deftest test-mod-resource-in-wire-catalog
  (let [old-resources (set (map convert-tags (wire-resources wire-catalog)))
        result (mod-resource-in-wire-catalog wire-catalog)
        new-resources (set (map convert-tags (wire-resources result)))]
    (is (= 2 (count (wire-resources result))))
    (is (= 1 (count (set/intersection old-resources new-resources))))))

(deftest test-swap-edge-targets-in-catalog
  (let [catalog (:basic ex/catalogs)
        old-edges (:edges catalog)
        result (swap-edge-targets-in-catalog catalog)
        new-edges (:edges result)]
    (is (= 3 (count new-edges)))
    (is (= 1 (count (set/intersection old-edges new-edges))))))

(deftest test-swap-edge-targets-in-wire-catalog
  (let [old-edges (set (wire-edges wire-catalog))
        result (swap-edge-targets-in-wire-catalog wire-catalog)
        new-edges (set (wire-edges result))]
    (is (= 2 (count (wire-resources result))))
    (is (not-any? old-edges new-edges))))
