(ns puppetlabs.puppetdb.scf.hash-debug-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.hash-debug :refer :all]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [puppetlabs.puppetdb.scf.storage :as store]
            [puppetlabs.puppetdb.testutils :as tu]
            [fs.core :as fs]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as str]))

(defn persist-catalog
  "Adds the certname and full catalog to the database, returns the catalog map with
   the generated as as `:persisted-hash`"
  [{certname :certname :as catalog}]
  (store/add-certname! certname)
  (let [persisted-hash (store/add-catalog! catalog)]
    (assoc catalog :persisted-hash persisted-hash)))

(defn find-file
  "Finds files in `dir` with the given `suffix`. Useful for the debugging
   files that include a UUID in the prefix of the file name."
  [^String suffix dir]
  (first
   (for [f (fs/list-dir dir)
         :when (.endsWith f suffix)]
     (str dir "/" f))))

(def ^{:doc "Reads a catalog debugging clojure file from the file system."}
  slurp-clj
  (comp read-string slurp find-file))

(def ^{:doc "Reads/parses a JSON catalog debugging file from the file system."}
  slurp-json
  (comp json/parse-string slurp find-file))

(defn file-uuid
  "Strips the UUID out of the hash debug file name."
  [path]
  (-> (fs/base-name path)
      (str/split #"_")
      second))

(defn assert-debug-files-present
  "Checks the hash debug file directory for the correct
   number of files and that they all have the same UUID."
  [debug-dir]
  (let [debug-files (fs/list-dir debug-dir)
        uuid (file-uuid (first debug-files))]
    (is (= 5 (count debug-files)))
    (is (every? #(kitchensink/string-contains? uuid %) debug-files))))

(deftest debug-catalog-output
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))
            new-catalog (assoc-in (:basic catalogs)
                                  [:resources {:type "File"
                                               :title "/etc/foobar/bazv2"}]
                                  {:type "File"
                                   :title "/etc/foobar/bazv2"
                                   :file nil
                                   :line nil
                                   :parameters {}
                                   :resource "asdf"
                                   :tags []
                                   :exported false})
            new-hash (shash/catalog-similarity-hash new-catalog)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash new-catalog)

        (assert-debug-files-present debug-dir)

        (let [{old-edn-res :resources
               old-edn-edges :edges
               :as old-edn} (slurp-clj "catalog-old.edn" debug-dir)
               {new-edn-res :resources
                new-edn-edges :edges
                :as new-edn} (slurp-clj "catalog-new.edn" debug-dir)
                {old-json-res "resources"
                 old-json-edges "edges"
                 :as old-json} (slurp-json "catalog-old.json" debug-dir)
                 {new-json-res "resources"
                  new-json-edges "edges"
                  :as new-json} (slurp-json "catalog-new.json" debug-dir)
                  catalog-metadata (slurp-json "catalog-metadata.json" debug-dir)]

          (is (some #(= "/etc/foobar/bazv2" (:title %)) new-edn-res))
          (is (some #(= "/etc/foobar/bazv2" (get % "title")) new-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (get % "title")) old-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (:title %)) old-edn-res))

          (is (every? (comp keyword? :relationship)  old-edn-edges)
              "Edge relationships from the DB should be converted to keywords")
          (is (every? keyword? (mapcat (comp keys :parameters) old-edn-res))
              "Parameter keys from the DB should be converted to keywords")

          (is (seq old-edn-res))
          (is (seq old-edn-edges))
          (is (seq old-json-res))
          (is (seq old-json-edges))

          (are [metadata-key] (contains? catalog-metadata metadata-key)
               "java version"
               "new catalog hash"
               "old catalog hash"
               "database name"
               "database version")

          (are [metadata-key] (and (kitchensink/string-contains? (:certname new-catalog)
                                                                 (get catalog-metadata metadata-key))
                                   (.startsWith (get catalog-metadata metadata-key) debug-dir))
               "old catalog path - edn"
               "new catalog path - edn"
               "old catalog path - json"
               "new catalog path - json")

          (is (not= (get catalog-metadata "new catalog hash")
                    (get catalog-metadata "old catalog hash"))))))))

(deftest debug-catalog-output-filename-uniqueness
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))

            new-catalog-1 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv2"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-1 (shash/catalog-similarity-hash new-catalog-1)

            new-catalog-2 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv3"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-2 (shash/catalog-similarity-hash new-catalog-2)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash-1 new-catalog-1)
        (debug-catalog debug-dir new-hash-2 new-catalog-2)
        (is (= 10 (count (fs/list-dir debug-dir))))))))
