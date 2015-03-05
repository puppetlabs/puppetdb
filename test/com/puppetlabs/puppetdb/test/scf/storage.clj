(ns com.puppetlabs.puppetdb.test.scf.storage
  (:require [com.puppetlabs.puppetdb.catalog.utils :as catutils]
            [com.puppetlabs.random :as random]
            [com.puppetlabs.puppetdb.report.utils :as reputils]
            [com.puppetlabs.puppetdb.reports :as report-val]
            [com.puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [com.puppetlabs.puppetdb.scf.migrate :as migrate]
            [clojure.walk :as walk]
            [com.puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [throw+]]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [metrics.histograms :refer [sample histogram]]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [clojure.string :as str]
            [com.puppetlabs.puppetdb.examples :refer [catalogs]]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [com.puppetlabs.puppetdb.testutils.reports :refer :all]
            [com.puppetlabs.puppetdb.testutils.events :refer :all]
            [com.puppetlabs.puppetdb.query.reports :refer [is-latest-report?]]
            [com.puppetlabs.puppetdb.scf.storage :refer :all]
            [clojure.test :refer :all]
            [clojure.math.combinatorics :refer [combinations subsets]]
            [clj-time.core :refer [ago from-now now days]]
            [clj-time.coerce :refer [to-timestamp to-string]]
            [com.puppetlabs.jdbc :refer [query-to-vec with-transacted-connection
                                         convert-result-arrays]]
            [com.puppetlabs.puppetdb.fixtures :refer :all]))

(use-fixtures :each with-test-db)

(defn reset-db!
  []
  (tu/clear-db-for-testing!)
  (migrate/migrate!))

(defn-validated factset-map :- {s/Str s/Str}
  "Return all facts and their values for a given certname as a map"
  [certname :- String]
  (sql/with-query-results result-set
    ["SELECT fp.path as name,
             COALESCE(fv.value_string,
                      cast(fv.value_integer as text),
                      cast(fv.value_boolean as text),
                      cast(fv.value_float as text),
                      '') as value
             FROM factsets fs
                  INNER JOIN facts as f on fs.id = f.factset_id
                  INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                  INNER JOIN fact_values as fv on f.fact_value_id = fv.id
             WHERE fp.depth = 0 AND
                   fs.certname = ?"
     certname]
    (zipmap (map :name result-set)
            (map :value result-set))))

(deftest fact-persistence
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}]
      (add-certname! certname)

      (is (nil?
           (sql/transaction
            (factset-timestamp "some_certname"))))
      (is (empty? (factset-map "some_certname")))

      (add-facts! {:name certname
                   :values facts
                   :timestamp (-> 2 days ago)
                   :environment nil
                   :producer-timestamp nil})
      (testing "should have entries for each fact"
        (is (= (query-to-vec
                "SELECT fp.path as name,
                        COALESCE(fv.value_string,
                                 cast(fv.value_integer as text),
                                 cast(fv.value_boolean as text),
                                 cast(fv.value_float as text),
                                 '') as value,
                        fs.certname
                 FROM factsets fs
                   INNER JOIN facts as f on fs.id = f.factset_id
                   INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                   INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                 WHERE fp.depth = 0
                 ORDER BY name")
               [{:certname certname :name "domain" :value "mydomain.com"}
                {:certname certname :name "fqdn" :value "myhost.mydomain.com"}
                {:certname certname :name "hostname" :value "myhost"}
                {:certname certname :name "kernel" :value "Linux"}
                {:certname certname :name "operatingsystem" :value "Debian"}]))

        (is (sql/transaction
             (factset-timestamp "some_certname")))
        (is (= facts (factset-map "some_certname"))))

      (testing "should add the certname if necessary"
        (is (= (query-to-vec "SELECT name FROM certnames")
               [{:name certname}])))
      (testing "producer-timestamp should store nil"
        (is (= (query-to-vec "SELECT producer_timestamp FROM factsets")
               [{:producer_timestamp nil}])))
      (testing "replacing facts"
        ;;Ensuring here that new records are inserted, updated
        ;;facts are updated (not deleted and inserted) and that
        ;;the necessary deletes happen
        (tu/with-wrapped-fn-args [adds sql/insert-records
                                  updates sql/update-values]
          (let [new-facts {"domain" "mynewdomain.com"
                           "fqdn" "myhost.mynewdomain.com"
                           "hostname" "myhost"
                           "kernel" "Linux"
                           "uptime_seconds" "3600"}
                current-time (now)]
            (replace-facts! {:name certname
                             :values new-facts
                             :environment "DEV"
                             :producer-timestamp current-time
                             :timestamp current-time})
            (testing "should have only the new facts"
              (is (= (query-to-vec
                      "SELECT fp.path as name,
                              COALESCE(fv.value_string,
                                       cast(fv.value_integer as text),
                                       cast(fv.value_boolean as text),
                                       cast(fv.value_float as text),
                                       '') as value
                       FROM factsets fs
                         INNER JOIN facts as f on fs.id = f.factset_id
                         INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                         INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                       WHERE fp.depth = 0
                       ORDER BY name")
                     [{:name "domain" :value "mynewdomain.com"}
                      {:name "fqdn" :value "myhost.mynewdomain.com"}
                      {:name "hostname" :value "myhost"}
                      {:name "kernel" :value "Linux"}
                      {:name "uptime_seconds" :value "3600"}])))
            (testing "producer-timestamp should store current time"
              (is (= (query-to-vec "SELECT producer_timestamp FROM factsets")
                     [{:producer_timestamp (to-timestamp current-time)}])))
            (testing "should update existing keys"
              (is (some #{{:timestamp (to-timestamp current-time)
                           :environment_id 1
                           :producer_timestamp (to-timestamp current-time)}}
                        ;; Again we grab the pertinent non-id bits
                        (map (fn [itm] (last itm)) @updates)))
              (is (some (fn [update-call]
                          (and (= :factsets (first update-call))
                               (:timestamp (last update-call))))
                        @updates)))
            (testing "should only insert uptime_seconds"
              (is (some #{[:fact_paths {:path "uptime_seconds"
                                        :name "uptime_seconds"
                                        :depth 0}]}
                        @adds))))))

      (testing "replacing all new facts"
        (delete-certname-facts! certname)
        (replace-facts! {:name certname
                         :values facts
                         :environment "DEV"
                         :producer-timestamp nil
                         :timestamp (now)})
        (is (= facts (factset-map "some_certname"))))

      (testing "replacing all facts with new ones"
        (delete-certname-facts! certname)

        (add-facts! {:name certname
                     :values facts
                     :timestamp (-> 2 days ago)
                     :environment nil
                     :producer-timestamp nil})
        (replace-facts! {:name certname
                         :values {"foo" "bar"}
                         :environment "DEV"
                         :producer-timestamp nil
                         :timestamp (now)})
        (is (= {"foo" "bar"} (factset-map "some_certname"))))

      (testing "replace-facts with only additions"
        (let [fact-map (factset-map "some_certname")]
          (replace-facts! {:name certname
                           :values (assoc fact-map "one more" "here")
                           :environment "DEV"
                           :producer-timestamp nil
                           :timestamp (now)})
          (is (= (assoc fact-map  "one more" "here")
                 (factset-map "some_certname")))))

      (testing "replace-facts with no change"
        (let [fact-map (factset-map "some_certname")]
          (replace-facts! {:name certname
                           :values fact-map
                           :environment "DEV"
                           :producer-timestamp nil
                           :timestamp (now)})
          (is (= fact-map
                 (factset-map "some_certname"))))))))

(deftest fact-persistance-with-environment
    (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}]
      (add-certname! certname)

      (is (nil?
           (sql/transaction
            (factset-timestamp "some_certname"))))
      (is (empty? (factset-map "some_certname")))
      (is (nil? (environment-id "PROD")))

      (add-facts! {:name certname
                   :values facts
                   :timestamp (-> 2 days ago)
                   :environment "PROD"
                   :producer-timestamp nil})

      (testing "should have entries for each fact"
        (is (= facts
               (into {} (map (juxt :name :value)
                             (query-to-vec
                              "SELECT fp.path as name,
                                      COALESCE(fv.value_string,
                                               cast(fv.value_integer as text),
                                               cast(fv.value_boolean as text),
                                               cast(fv.value_float as text),
                                               '') as value
                               FROM factsets fs
                                 INNER JOIN facts as f on fs.id = f.factset_id
                                 INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                                 INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                               WHERE fp.depth = 0
                               ORDER BY name")))))

        (is (= [{:certname "some_certname"
                 :environment_id (environment-id "PROD")}]
               (query-to-vec "SELECT certname, environment_id FROM factsets"))))

      (is (nil? (environment-id "DEV")))

      (update-facts!
       {:name certname
        :values facts
        :timestamp (-> 1 days ago)
        :environment "DEV"
        :producer-timestamp nil})

      (testing "should have the same entries for each fact"
        (is (= facts
               (into {} (map (juxt :name :value)
                             (query-to-vec
                              "SELECT fp.path as name,
                                      COALESCE(fv.value_string,
                                               cast(fv.value_integer as text),
                                               cast(fv.value_boolean as text),
                                               cast(fv.value_float as text),
                                               '') as value
                               FROM factsets fs
                                 INNER JOIN facts as f on fs.id = f.factset_id
                                 INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                                 INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                               WHERE fp.depth = 0
                               ORDER BY name")))))

        (is (= [{:certname "some_certname"
                 :environment_id (environment-id "DEV")}]
               (query-to-vec "SELECT certname, environment_id FROM factsets")))))))

(deftest fact-path-value-gc
  (letfn [(facts-now [c v]
            {:name c :values v
             :environment nil :timestamp (now) :producer-timestamp nil})
          (paths [& fact-sets]
            (set (for [k (mapcat keys fact-sets)] {:path k :name k :depth 0})))
          (values [& fact-sets] (set (mapcat vals fact-sets)))
          (db-paths []
            (set (query-to-vec "SELECT path, name, depth FROM fact_paths")))
          (db-vals []
            (set (mapv :value (query-to-vec
                               ;; Note: currently can't distinguish 10 from "10".
                               "SELECT COALESCE(fv.value_string,
                                                cast(fv.value_integer as text),
                                                cast(fv.value_boolean as text),
                                                cast(fv.value_float as text),
                                                '') as value
                                  FROM fact_values fv"))))]
    (testing "during add/replace (generally)"
      ;; Keep resetting the db facts to match the current state of
      ;; @fact-x and @facts-y, and then verify that fact_paths always
      ;; contains exactly the set of keys across both, and fact_values
      ;; contains exactly the set of values across both.
      (let [facts-x (atom  {"a" "1" "b" "2" "c" "3"})
            facts-y (atom  {})]
        (add-certname! "c-x")
        (add-certname! "c-y")
        (add-facts! (facts-now "c-x" @facts-x))
        (is (= (db-paths) (paths @facts-x @facts-y)))
        (is (= (db-vals) (values @facts-x @facts-y)))
        (reset! facts-y {"d" "4"})
        (add-facts! (facts-now "c-y" @facts-y))
        (is (= (db-paths) (paths @facts-x @facts-y)))
        (is (= (db-vals) (values @facts-x @facts-y)))
        ;; Check that setting c-y to match c-x drops d and 4.
        (reset! facts-y @facts-x)
        (update-facts! (facts-now "c-y" @facts-x))
        (is (= (db-paths) (paths @facts-x @facts-y)))
        (is (= (db-vals) (values @facts-x @facts-y)))
        ;; Check that changing c-y's c to 2 does nothing.
        (swap! facts-y assoc "c" "2")
        (update-facts! (facts-now "c-y" @facts-y))
        (is (= (db-paths) (paths @facts-x @facts-y)))
        (is (= (db-vals) (values @facts-x @facts-y)))
        ;; Check that changing c-x's c to 2 drops 3.
        (swap! facts-x assoc "c" "2")
        (update-facts! (facts-now "c-x" @facts-x))
        (is (= (db-paths) (paths @facts-x @facts-y)))
        (is (= (db-vals) (values @facts-x @facts-y)))
        ;; CLEAR ALL THE FACTS!?
        (replace-facts! (facts-now "c-y" {}))))
    (testing "during replace, when value is only referred to by the same factset"
      (reset-db!)
      (let [facts {"a" "1" "b" "1"}]
        (add-certname! "c-x")
        (replace-facts! (facts-now "c-x" facts))
        (replace-facts! (facts-now "c-x" (assoc facts "b" "2")))))))

(def catalog (:basic catalogs))
(def certname (:name catalog))
(def current-time (str (now)))

(deftest catalog-persistence
  (testing "Persisted catalogs"
    (add-certname! certname)
    (add-catalog! (assoc catalog :producer-timestamp current-time))

    (testing "should contain proper catalog metadata"
      (is (= (query-to-vec ["SELECT certname, api_version, catalog_version, producer_timestamp FROM catalogs"])
             [{:certname certname :api_version 1 :catalog_version "123456789" :producer_timestamp (to-timestamp current-time)}])))

    (testing "should contain a complete edges list"
      (is (= (query-to-vec [(str "SELECT r1.type as stype, r1.title as stitle, r2.type as ttype, r2.title as ttitle, e.type as etype "
                                 "FROM edges e, catalog_resources r1, catalog_resources r2 "
                                 "WHERE e.source=r1.resource AND e.target=r2.resource "
                                 "ORDER BY r1.type, r1.title, r2.type, r2.title, e.type")])
             [{:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar" :etype "contains"}
              {:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "contains"}
              {:stype "File" :stitle "/etc/foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "required-by"}])))

    (testing "should contain a complete resources list"
      (is (= (query-to-vec ["SELECT type, title FROM catalog_resources ORDER BY type, title"])
             [{:type "Class" :title "foobar"}
              {:type "File" :title "/etc/foobar"}
              {:type "File" :title "/etc/foobar/baz"}]))

      (testing "properly associated with the host"
        (is (= (query-to-vec ["SELECT c.certname, cr.type, cr.title
                                 FROM catalog_resources cr, catalogs c
                                 WHERE c.id=cr.catalog_id
                                 ORDER BY cr.type, cr.title"])
               [{:certname certname :type "Class" :title "foobar"}
                {:certname certname :type "File"  :title "/etc/foobar"}
                {:certname certname :type "File"  :title "/etc/foobar/baz"}])))

      (testing "with all parameters"
        (is (= (query-to-vec ["SELECT cr.type, cr.title, rp.name, rp.value FROM catalog_resources cr, resource_params rp WHERE rp.resource=cr.resource ORDER BY cr.type, cr.title, rp.name"])
               [{:type "File" :title "/etc/foobar" :name "ensure" :value (sutils/db-serialize "directory")}
                {:type "File" :title "/etc/foobar" :name "group" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar" :name "user" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar/baz" :name "ensure" :value (sutils/db-serialize "directory")}
                {:type "File" :title "/etc/foobar/baz" :name "group" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar/baz" :name "require" :value (sutils/db-serialize "File[/etc/foobar]")}
                {:type "File" :title "/etc/foobar/baz" :name "user" :value (sutils/db-serialize "root")}])))

      (testing "with all metadata"
        (let [result (query-to-vec ["SELECT cr.type, cr.title, cr.exported, cr.tags, cr.file, cr.line FROM catalog_resources cr ORDER BY cr.type, cr.title"])]
          (is (= (map #(assoc % :tags (sort (:tags %))) result)
                 [{:type "Class" :title "foobar" :tags [] :exported false :file nil :line nil}
                  {:type "File" :title "/etc/foobar" :tags ["class" "file" "foobar"] :exported false :file "/tmp/foo" :line 10}
                  {:type "File" :title "/etc/foobar/baz" :tags ["class" "file" "foobar"] :exported false :file "/tmp/bar" :line 20}])))))))

(deftest catalog-persistence-with-environment
  (let [other-certname "notbasic.catalogs.com"]
    (testing "Persisted catalogs"
      (add-certname! certname)
      (add-certname! other-certname)

      (is (nil? (environment-id "PROD")))

      (add-catalog! (assoc catalog :environment "PROD"))

      (testing "should persist environment if the environment is new"
        (let [id (environment-id "PROD")]
          (is (number? (environment-id "PROD")))
          (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                 (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

          (testing "Adding another catalog with the same environment should just use the existing environment"
            (add-catalog! (assoc catalog :environment "PROD" :name other-certname))

            (is (= [{:certname other-certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                   (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs where certname=?" other-certname])))))))))

(deftest updating-catalog-environment
  (testing "should persist environment if the environment is new"
    (let [prod-id (ensure-environment "PROD")
          dev-id (ensure-environment "DEV")]

      (add-certname! certname)
      (add-catalog! (assoc catalog :environment "DEV"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id dev-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

      (add-catalog! (assoc catalog :environment "PROD"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id prod-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"]))))))

(deftest catalog-replacement
  (testing "should noop if replaced by themselves"
    (add-certname! certname)
    (let [hash (add-catalog! catalog)]
      (replace-catalog! catalog (now))

      (is (= (query-to-vec ["SELECT name FROM certnames"])
             [{:name certname}]))

      (is (= (query-to-vec ["SELECT hash FROM catalogs"])
             [{:hash hash}])))))

(deftest edge-replacement-differential
  (testing "should do selective inserts/deletes when edges are modified just slightly"
    (add-certname! certname)
    (let [original-catalog (:basic catalogs)
          original-edges   (:edges original-catalog)
          modified-edges   (conj (disj original-edges {:source {:type "Class" :title "foobar"}
                                                       :target {:type "File" :title "/etc/foobar"}
                                                       :relationship :contains})
                                 {:source {:type "File" :title "/etc/foobar"}
                                  :target {:type "File" :title "/etc/foobar/baz"}
                                  :relationship :before})
          modified-catalog (assoc original-catalog :edges modified-edges)]
      ;; Add an initial catalog, we don't care to intercept the SQL yet
      (replace-catalog! original-catalog (now))

      (testing "ensure catalog-edges-map returns a predictable value"
        (is (= (catalog-edges-map certname)
               {["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                 "57495b553981551c5194a21b9a26554cd93db3d9"
                 "contains"] nil,
                 ["57495b553981551c5194a21b9a26554cd93db3d9"
                  "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                  "required-by"] nil,
                  ["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                   "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                   "contains"] nil})))

      ;; Lets intercept the insert/update/delete level so we can test it later
      ;; Here we only replace edges, so we can capture those specific SQL
      ;; operations
      (tu/with-wrapped-fn-args [adds sql/insert-rows
                                deletes sql/delete-rows]
        (let [resources    (:resources modified-catalog)
              refs-to-hash (reduce-kv (fn [i k v]
                                        (assoc i k (shash/resource-identity-hash v)))
                                      {} resources)]
          (replace-edges! certname modified-edges refs-to-hash)
          (testing "ensure catalog-edges-map returns a predictable value"
            (is (= (catalog-edges-map certname)
                   {["d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                     "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                     "contains"] nil,
                     ["57495b553981551c5194a21b9a26554cd93db3d9"
                      "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                      "required-by"] nil
                      ["57495b553981551c5194a21b9a26554cd93db3d9"
                       "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                       "before"] nil})))

          (testing "should only delete the 1 edge"
            (is (= [[:edges ["certname=? and source=? and target=? and type=?"
                             "basic.catalogs.com"
                             "d9b87fb0aaafa5f56cc49e9dbfa83b1c573c6e8a"
                             "57495b553981551c5194a21b9a26554cd93db3d9"
                             "contains"]]]
                   @deletes)))
          (testing "should only insert the 1 edge"
            (is (= [[:edges ["basic.catalogs.com"
                             "57495b553981551c5194a21b9a26554cd93db3d9"
                             "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                             "before"]]]
                   @adds)))
          (testing "when reran to check for idempotency"
            (reset! adds [])
            (reset! deletes [])
            (replace-edges! certname modified-edges refs-to-hash)
            (testing "should delete no edges"
              (is (empty? @deletes)))
            (testing "should insert no edges"
              (is (empty?@adds)))))))))

(deftest catalog-duplicates
  (testing "should share structure when duplicate catalogs are detected for the same host"
    (add-certname! certname)
    (let [hash (add-catalog! catalog)
          prev-dupe-num (.count (:duplicate-catalog metrics))
          prev-new-num  (.count (:updated-catalog metrics))]

      ;; Do an initial replacement with the same catalog
      (replace-catalog! catalog (now))
      (is (= 1 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
      (is (= 0 (- (.count (:updated-catalog metrics)) prev-new-num)))

      ;; Store a second catalog, with the same content save the version
      (replace-catalog! (assoc catalog :version "abc123") (now))
      (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
      (is (= 0 (- (.count (:updated-catalog metrics)) prev-new-num)))

      (is (= (query-to-vec ["SELECT name FROM certnames"])
             [{:name certname}]))

      (is (= (query-to-vec ["SELECT certname, hash FROM catalogs"])
             [{:hash hash
               :certname certname}]))

      (replace-catalog! (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :line] 20) (now))
      (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
      (is (= 1 (- (.count (:updated-catalog metrics)) prev-new-num))))))

(deftest catalog-manual-deletion
  (testing "should noop if replaced by themselves after using manual deletion"
    (add-certname! certname)
    (add-catalog! catalog)
    (delete-catalog! certname)
    (add-catalog! catalog)

    (is (= (query-to-vec ["SELECT name FROM certnames"])
           [{:name certname}]))))

(deftest catalog-deletion-verify
  (testing "should be removed when deleted"
    (add-certname! certname)
    (let [hash (add-catalog! catalog)]
      (delete-catalog! hash))

    (is (= (query-to-vec ["SELECT * FROM catalog_resources"])
           []))))

(deftest catalog-deletion-certnames
  (testing "when deleted, should leave certnames alone"
    (add-certname! certname)
    (add-catalog! catalog)
    (delete-catalog! certname)

    (is (= (query-to-vec ["SELECT name FROM certnames"])
           [{:name certname}]))))

(deftest catalog-deletion-otherhosts
  (testing "when deleted, should leave other hosts' resources alone"
    (add-certname! certname)
    (add-certname! "myhost2.mydomain.com")
    (let [hash1 (add-catalog! catalog)
          ;; Store the same catalog for a different host
          hash2 (add-catalog! (assoc catalog :name "myhost2.mydomain.com"))]
      (delete-catalog! hash1))

    ;; myhost should still be present in the database
    (is (= (query-to-vec ["SELECT name FROM certnames ORDER BY name"])
           [{:name certname} {:name "myhost2.mydomain.com"}]))

    ;; myhost1 should not have any catalogs associated with it
    ;; anymore
    (is (= (query-to-vec ["SELECT certname FROM catalogs"])
           [{:certname "myhost2.mydomain.com"}]))

    ;; All the other resources should still be there
    (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalog_resources"])
           [{:c 3}]))))

;; FIXME: should this and delete-certname-facts! go away once the new
;; local-gc tests are finished?
(deftest fact-delete-should-prune-paths-and-values
  (add-certname! certname)

  ;; Add some facts
  (let [facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"
               "networking" {"eth0" {"ipaddresses" ["192.168.0.11"]}}}]
    (add-facts! {:name certname
                 :values facts
                 :timestamp (-> 2 days ago)
                 :environment "ENV3"
                 :producer-timestamp nil}))
  (let [factset-id (:id (first (query-to-vec ["SELECT id from factsets"])))
        fact-value-ids (set (map :id (query-to-vec ["SELECT id from fact_values"])))]

    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_values"]))) 7))
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_paths"]))) 7))
    (delete-certname-facts! certname)
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_values"]))) 0))
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_paths"]))) 0))))

(deftest catalog-delete-with-gc-params
  (testing "when deleted but no GC should leave params"
    (add-certname! certname)
    (let [hash1 (add-catalog! catalog)]
      (delete-catalog! hash1))

    ;; All the params should still be there
    (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params"])
           [{:c 7}]))
    (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params_cache"])
           [{:c 3}])))

  (testing "when GC'ed, should leave no dangling params"
    (garbage-collect!)

    ;; And now they are gone
    (is (= (query-to-vec ["SELECT * FROM resource_params"])
           []))
    (is (= (query-to-vec ["SELECT * FROM resource_params_cache"])
           []))))

(deftest catalog-delete-with-gc-environments
  (testing "when deleted but no GC should leave environments"
    (add-certname! certname)

    ;; Add a catalog with an environment
    (let [catalog (assoc catalog :environment "ENV1")
          hash1 (add-catalog! catalog)]
      (delete-catalog! hash1))

    ;; Add a report with an environment
    (let [timestamp     (now)
          report        (-> (:basic reports)
                            (assoc :environment "ENV2")
                            (assoc :end-time (to-string (ago (days 5)))))
          report-hash   (shash/report-identity-hash report)
          certname      (:certname report)]
      (store-example-report! report timestamp)
      (delete-reports-older-than! (ago (days 2))))

    ;; Add some facts
    (let [facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}]
      (add-facts! {:name certname
                   :values facts
                   :timestamp (-> 2 days ago)
                   :environment "ENV3"
                   :producer-timestamp nil})
      (delete-certname-facts! certname))

    (is (= (query-to-vec ["SELECT COUNT(*) as c FROM environments"])
           [{:c 3}])))

  (testing "when GC should leave no dangling environments"
    (garbage-collect!)

    (is (= (query-to-vec ["SELECT * FROM environments"])
           []))))

(deftest delete-with-gc-report-statuses
  (add-certname! certname)

  (let [timestamp     (now)
        report        (:basic reports)
        certname      (:certname report)]
    (store-example-report! report timestamp)

    (is (= [{:c 1}] (query-to-vec ["SELECT COUNT(*) as c FROM report_statuses"])))

    (delete-reports-older-than! (ago (days 2)))

    (is (= [{:c 1}] (query-to-vec ["SELECT COUNT(*) as c FROM report_statuses"])))
    (garbage-collect!)
    (is (= [{:c 0}] (query-to-vec ["SELECT COUNT(*) as c FROM report_statuses"])))))

(deftest catalog-bad-input
  (testing "should noop"
    (testing "on bad input"
      (is (thrown? clojure.lang.ExceptionInfo (add-catalog! {})))

      ; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
            [{:nrows 0}])))))

(defn foobar->foobar2 [x]
  (if (and (string? x) (= x "/etc/foobar"))
    "/etc/foobar2"
    x))

(defn table-args
  "Many of the java.jdbc functions accept a table name as the first arg, this
   function grabs that argument"
  [coll]
  (map first coll))

(defn remove-edge-changes
  "Remove the edge related changes from the `coll` of function call arguments"
  [coll]
  (remove #(= :edges (first %)) coll))

(defn sort= [& args]
  (apply = (map sort args)))

(deftest existing-catalog-update
  (let [{certname :name :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]

    (testing "inserting new catalog with resources"

      (add-certname! certname)
      (is (empty? (query-to-vec "SELECT * from catalogs where certname=?" certname)))

      (add-catalog! catalog nil old-date)

      (let [results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first results)]

        (is (= 1 (count results)))
        (is (= (to-timestamp old-date) (to-timestamp timestamp)))))

    (testing "changing a resource title"
      (let [{orig-id :id
             orig-tx-id :transaction_uuid
             orig-timestamp :timestamp} (first (query-to-vec "SELECT id from catalogs where certname=?" certname))
             updated-catalog (walk/prewalk foobar->foobar2 (:basic catalogs))
             new-uuid (kitchensink/uuid)
             metrics-map metrics]

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c INNER JOIN catalog_resources cr ON c.id = cr.catalog_id
                                   WHERE c.certname=?" certname))))

        (tu/with-wrapped-fn-args [inserts sql/insert-records
                                  deletes sql/delete-rows
                                  updates sql/update-values]
          (with-redefs [metrics (assoc metrics-map :catalog-volatility (histogram [ns-str "default" (str (gensym))]))]
            (add-catalog! (assoc updated-catalog :transaction-uuid new-uuid) nil yesterday)

            ;; 2 edge deletes
            ;; 2 edge inserts
            ;; 1 params insert
            ;; 1 params cache insert
            ;; 1 catalog_resource insert
            ;; 1 catalog_resource delete
            (is (= 8.0 (apply + (sample (:catalog-volatility metrics))))))

          (is (sort= [:resource_params_cache :resource_params :catalog_resources]
                     (table-args @inserts)))
          (is (= [:catalogs]
                 (table-args @updates)))
          (is (= [[:catalog_resources ["catalog_id = ? and type = ? and title = ?" 1 "File" "/etc/foobar"]]]
                 (remove-edge-changes @deletes))))

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar2"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c INNER JOIN catalog_resources cr ON c.id = cr.catalog_id
                                   WHERE c.certname=?" certname))))

        (let [results (query-to-vec "SELECT id, timestamp, transaction_uuid from catalogs where certname=?" certname)
              {new-timestamp :timestamp
               new-tx-id :transaction_uuid
               new-id :id} (first results)]

          (is (= 1 (count results)))
          (is (= (to-timestamp yesterday) (to-timestamp new-timestamp)))
          (is (= new-tx-id new-uuid))
          (is (= orig-id new-id))
          (is (not= orig-tx-id new-tx-id))
          (is (not= orig-timestamp new-timestamp)))))))

(deftest add-resource-to-existing-catalog
  (let [{certname :name :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]
    (add-certname! certname)
    (add-catalog! catalog nil old-date)

    (is (= 3 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)))))

    (tu/with-wrapped-fn-args [inserts sql/insert-records
                           updates sql/update-values
                           deletes sql/delete-rows]
      (add-catalog! (assoc-in catalog
                              [:resources {:type "File" :title "/etc/foobar2"}]
                              {:type "File"
                               :title "/etc/foobar2"
                               :exported   false
                               :file       "/tmp/foo2"
                               :line       20
                               :tags       #{"file" "class" "foobar"}
                               :parameters {:ensure "directory"
                                            :group  "root"
                                            :user   "root"}})
                    nil old-date)

      (is (sort= [:resource_params_cache :resource_params :catalog_resources]
                 (table-args @inserts)))
      (is (= [:catalogs]
             (table-args @updates)))
      (is (empty? @deletes)))

    (is (= 4 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)))))))

(deftest change-line-resource-metadata
  (add-certname! certname)
  (add-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:line nil}
             {:line 10}
             {:line 20}}
           (set (query-to-vec "SELECT line FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname))))

    (add-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :line 1000) resources))))

    (is (= [{:line 1000}
            {:line 1000}
            {:line 1000}]
           (query-to-vec "SELECT line FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)))))

(deftest change-exported-resource-metadata
  (add-certname! certname)
  (add-catalog! catalog)

  (testing "changing exported"
    (is (= #{{:exported false
              :title "foobar"}
             {:exported false
              :title "/etc/foobar"}
             {:exported false
              :title "/etc/foobar/baz"}}
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname))))

    (add-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (-> resources
                                   (assoc-in [{:type "Class" :title "foobar"} :exported] true)
                                   (assoc-in [{:type "File" :title "/etc/foobar/baz"} :exported] true)))))
    (is (= #{{:exported true
              :title "foobar"}
             {:exported false
              :title "/etc/foobar"}
             {:exported true
              :title "/etc/foobar/baz"}}
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname))))))

(deftest change-file-resource-metadata
  (add-certname! certname)
  (add-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:title "foobar"
              :file nil}
             {:title "/etc/foobar"
              :file "/tmp/foo"}
             {:title "/etc/foobar/baz"
              :file "/tmp/bar"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname))))

    (add-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :file "/tmp/foo.pp") resources))))

    (is (= #{{:title "foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar/baz"
              :file "/tmp/foo.pp"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname))))))

(defn tags->set
  "Converts tags from a vector to a set"
  [result-set]
  (mapv (fn [result]
          (update-in result [:tags] set))
        result-set))

(deftest change-tags-on-resource
  (add-certname! certname)
  (add-catalog! catalog)

  (is (= #{{:title "foobar"
            :tags #{}
            :line nil}
           {:title "/etc/foobar"
            :tags #{"file" "class" "foobar"}
            :line 10}
           {:title "/etc/foobar/baz"
            :tags #{"file" "class" "foobar"}
            :line 20}}
         (-> (query-to-vec "SELECT title, tags, line FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)
             convert-result-arrays
             tags->set
             set)))

  (add-catalog! (update-in catalog [:resources]
                           (fn [resources]
                             (-> resources
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :tags] #{"totally" "different" "tags"})
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :line] 500)))))
  (is (= #{{:title "foobar"
            :tags #{}
            :line nil}
           {:title "/etc/foobar"
            :line 500
            :tags #{"totally" "different" "tags"}}
           {:title "/etc/foobar/baz"
            :line 20
            :tags #{"file" "class" "foobar"}}}
         (-> (query-to-vec "SELECT title, tags, line FROM catalog_resources WHERE catalog_id = (select id from catalogs where certname = ?)" certname)
             convert-result-arrays
             tags->set
             set))))

(deftest removing-resources
  (let [{certname :name :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))
        catalog-with-extra-resource (assoc-in catalog
                                              [:resources {:type "File" :title "/etc/the-foo"}]
                                              {:type       "File"
                                               :title      "/etc/the-foo"
                                               :exported   false
                                               :file       "/tmp/the-foo"
                                               :line       10
                                               :tags       #{"file" "class" "the-foo"}
                                               :parameters {:ensure "directory"
                                                            :group  "root"
                                                            :user   "root"}})]
    (add-certname! certname)
    (add-catalog! catalog-with-extra-resource nil old-date)

    (let [catalog-id (:id (first (query-to-vec "SELECT id from catalogs where certname=?" certname)))]
      (is (= 4 (count (query-to-vec "SELECT * from catalog_resources where catalog_id = ?" catalog-id))))

      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]

        (add-catalog! catalog nil yesterday)
        (is (empty? @inserts))
        (is (= [:catalogs]
               (table-args @updates)))
        (is (= [:catalog_resources]
               (table-args @deletes))))

      (let [catalog-results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first catalog-results)
            resources (set (query-to-vec "SELECT type, title from catalog_resources where catalog_id = ?" catalog-id))]

        (is (= 1 (count catalog-results)))
        (is (= 3 (count resources)))
        (is (= (set (keys (:resources catalog)))
               resources))
        (is (= (to-timestamp yesterday) (to-timestamp timestamp)))))))

(defn foobar-params []
  (sql/with-query-results result-set
    ["SELECT p.name AS k, p.value AS v
      FROM catalog_resources cr, catalogs c, resource_params p
      WHERE cr.catalog_id = c.id AND cr.resource = p.resource AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :name]) "File" "/etc/foobar"]
    (reduce (fn [acc row]
              (assoc acc (keyword (:k row))
                     (json/parse-string (:v row))))
            {} result-set)))

(defn foobar-params-cache []
  (sql/with-query-results result-set
    ["SELECT rpc.parameters as params
      FROM catalog_resources cr, catalogs c, resource_params_cache rpc
      WHERE cr.catalog_id = c.id AND cr.resource = rpc.resource AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :name]) "File" "/etc/foobar"]
    (-> result-set
        first
        :params
        (json/parse-string true))))

(defn foobar-param-hash []
  (sql/with-query-results result-set
    ["SELECT cr.resource hash
      FROM catalog_resources cr, catalogs c
      WHERE cr.catalog_id = c.id AND certname=? AND cr.type=? AND cr.title=?"
     (get-in catalogs [:basic :name]) "File" "/etc/foobar"]
    (-> result-set
        first
        :hash)))

(deftest catalog-resource-parameter-changes
  (let [{certname :name :as catalog} (:basic catalogs)
        old-date (ago (days 2))
        yesterday (ago (days 1))]
    (add-certname! certname)
    (add-catalog! catalog nil old-date)

    (let [orig-resource-hash (foobar-param-hash)
          add-param-catalog (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters :uid] "100")]
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))

      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]

        (add-catalog! add-param-catalog nil yesterday)
        (is (sort= [:catalogs :catalog_resources]
                   (table-args @updates)))

        (is (empty? (remove-edge-changes @deletes)))

        (is (sort= [:resource_params_cache :resource_params]
                   (table-args @inserts))))

      (is (not= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))
      (tu/with-wrapped-fn-args [inserts sql/insert-records
                                updates sql/update-values
                                deletes sql/delete-rows]
        (add-catalog! catalog nil old-date)

        (is (empty? @inserts))
        (is (empty? (remove #(= :edges (first %)) @deletes)))
        (is (= (sort [:catalog_resources :catalogs])
               (sort (map first @updates)))))

      (is (= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache))))))

(deftest catalog-referential-integrity-violation
  (testing "on input that violates referential integrity"
    ; This catalog has an edge that points to a non-existant resource
    (let [catalog (:invalid catalogs)]
      (is (thrown? clojure.lang.ExceptionInfo (add-catalog! catalog)))

      ; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
            [{:nrows 0}])))))

(deftest node-deactivation
  (let [certname        "foo.example.com"
        query-certnames #(query-to-vec ["select name, deactivated from certnames"])
        deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]
    (add-certname! certname)

    (testing "deactivating a node"
      (testing "should mark the node as deactivated"
        (deactivate-node! certname)
        (let [result (first (query-certnames))]
          (is (= certname (:name result)))
          (is (deactivated? result))))

      (testing "should not change the node if it's already inactive"
        (let [original (query-certnames)]
          (deactivate-node! certname)
          (is (= original (query-certnames))))))

    (testing "activating a node"
      (testing "should activate the node if it was inactive"
        (activate-node! certname)
        (is (= (query-certnames) [{:name certname :deactivated nil}])))

      (testing "should do nothing if the node is already active"
        (let [original (query-certnames)]
          (activate-node! certname)
          (is (= original (query-certnames))))))

    (testing "auto-reactivated based on a command"
      (let [before-deactivating (to-timestamp (ago (days 1)))
            after-deactivating  (to-timestamp (from-now (days 1)))]
        (testing "should activate the node if the command happened after it was deactivated"
          (deactivate-node! certname)
          (is (= true (maybe-activate-node! certname after-deactivating)))
          (is (= (query-certnames) [{:name certname :deactivated nil}])))

        (testing "should not activate the node if the command happened before it was deactivated"
          (deactivate-node! certname)
          (is (= false (maybe-activate-node! certname before-deactivating)))
          (let [result (first (query-certnames))]
            (is (= certname (:name result)))
            (is (deactivated? result))))

        (testing "should do nothing if the node is already active"
          (activate-node! certname)
          (is (= true (maybe-activate-node! certname (now))))
          (is (= (query-certnames) [{:name certname :deactivated nil}])))))))

(deftest node-staleness-age
  (testing "retrieving stale nodes based on age"
    (let [query-certnames #(query-to-vec ["select name, deactivated from certnames order by name"])
          deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]

      (testing "should return nothing if all nodes are more recent than max age"
        (let [catalog (:empty catalogs)
              certname (:name catalog)]
          (add-certname! certname)
          (replace-catalog! catalog (now))
          (is (= (stale-nodes (ago (days 1))) [])))))))

(deftest node-stale-catalogs-facts
  (testing "should return nodes with a mixture of stale catalogs and facts (or neither)"
    (let [mutators [#(replace-catalog! (assoc (:empty catalogs) :name "node1") (ago (days 2)))
                    #(replace-facts! {:name "node1"
                                      :values {"foo" "bar"}
                                      :environment "DEV"
                                      :producer-timestamp "2014-07-10T22:33:54.781Z"
                                      :timestamp (ago (days 2))})]]
      (add-certname! "node1")
      (doseq [func-set (subsets mutators)]
        (dorun (map #(%) func-set))
        (is (= (stale-nodes (ago (days 1))) ["node1"]))))))

(deftest node-max-age
  (testing "should only return nodes older than max age, and leave others alone"
    (let [catalog (:empty catalogs)]
      (add-certname! "node1")
      (add-certname! "node2")
      (replace-catalog! (assoc catalog :name "node1") (ago (days 2)))
      (replace-catalog! (assoc catalog :name "node2") (now))

      (is (= (set (stale-nodes (ago (days 1)))) #{"node1"})))))

(deftest node-purge
  (testing "should purge only nodes which were deactivated before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (deactivate-node! "node1")
    (with-redefs [now (constantly (ago (days 10)))]
      (deactivate-node! "node2"))

    (purge-deactivated-nodes! (ago (days 5)))

    (is (= (map :name (query-to-vec "SELECT name FROM certnames ORDER BY name ASC"))
           ["node1" "node3"]))))

;; Report tests

(let [timestamp     (now)
      report        (:basic reports)
      report-hash   (shash/report-identity-hash report)
      certname      (:certname report)]

  (deftest report-storage
    (testing "should store reports"
      (store-example-report! report timestamp)

      (is (= (query-to-vec ["SELECT certname FROM reports"])
            [{:certname (:certname report)}]))

      (is (= (query-to-vec ["SELECT hash FROM reports"])
            [{:hash report-hash}])))

    (testing "should store report with long puppet version string"
      (store-example-report!
        (assoc report
          :puppet-version "3.2.1 (Puppet Enterprise 3.0.0-preview0-168-g32c839e)") timestamp)))

  (deftest report-storage-with-environment
    (is (nil? (environment-id "DEV")))

    (store-example-report! (assoc report :environment "DEV") timestamp)

    (is (number? (environment-id "DEV")))

    (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
           [{:certname (:certname report)
             :environment_id (environment-id "DEV")}])))

  (deftest report-storage-with-status
    (is (nil? (status-id "unchanged")))

    (store-example-report! (assoc report :status "unchanged") timestamp)

    (is (number? (status-id "unchanged")))

    (is (= (query-to-vec ["SELECT certname, status_id FROM reports"])
           [{:certname (:certname report)
             :status_id (status-id "unchanged")}])))

  (deftest report-storage-with-existing-environment
    (testing "should store reports"
      (let [env-id (ensure-environment "DEV")]

        (store-example-report! (assoc report :environment "DEV") timestamp)

        (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
               [{:certname (:certname report)
                 :environment_id env-id}])))))

  (deftest latest-report
    (testing "should flag report as 'latest'"
      (let [node        (:certname report)
            report-hash (:hash (store-example-report! report timestamp))]
        (is (is-latest-report? node report-hash))
        (let [new-report-hash (:hash (store-example-report!
                                        (-> report
                                          (assoc :configuration-version "bar")
                                          (assoc :end-time (now)))
                                        timestamp))]
          (is (is-latest-report? node new-report-hash))
          (is (not (is-latest-report? node report-hash)))))))


  (deftest report-cleanup
    (testing "should delete reports older than the specified age"
      (let [report1       (assoc report :end-time (to-string (ago (days 5))))
            report1-hash  (:hash (store-example-report! report1 timestamp))
            report2       (assoc report :end-time (to-string (ago (days 2))))
            report2-hash  (:hash (store-example-report! report2 timestamp))
            certname      (:certname report1)
            _             (delete-reports-older-than! (ago (days 3)))
            expected      (expected-reports [(assoc report2 :hash report2-hash)])
            actual        (reports-query-result :v4 ["=" "certname" certname])]
        (is (= expected actual)))))

  (deftest resource-events-cleanup
    (testing "should delete all events for reports older than the specified age"
      (let [report1       (assoc report :end-time (to-string (ago (days 5))))
            report1-hash  (:hash (store-example-report! report1 timestamp))
            report2       (assoc report :end-time (to-string (ago (days 2))))
            report2-hash  (:hash (store-example-report! report2 timestamp))
            certname      (:certname report1)
            _             (delete-reports-older-than! (ago (days 3)))
            expected      #{}
            actual        (resource-events-query-result :latest ["=" "report" report1-hash])]
        (is (= expected actual))))))

(defn with-db-version [db version f]
  (with-redefs-fn {#'sutils/sql-current-connection-database-name (constantly db)
                   #'sutils/sql-current-connection-database-version (constantly version)}
    f))

(deftest db-deprecation?
  (testing "should return a string if db is deprecated"
    (are [db version result]
      (with-db-version db version
        (fn []
          (is (= result (db-deprecated?)))))
      "PostgreSQL" [8 4] "PostgreSQL DB versions 8.4 - 9.1 are deprecated and won't be supported in the future."
      "PostgreSQL" [9 0] "PostgreSQL DB versions 8.4 - 9.1 are deprecated and won't be supported in the future."
      "PostgreSQL" [9 1] "PostgreSQL DB versions 8.4 - 9.1 are deprecated and won't be supported in the future."
      "PostgreSQL" [9 2] nil
      "PostgreSQL" [9 3] nil
      "PostgreSQL" [9 4] nil)))

(deftest test-db-unsupported?
  (testing "should return a string if db is deprecated"
    (are [db version result]
      (with-db-version db version
        (fn []
          (is (= result (db-unsupported?)))))
      "PostgreSQL" [8 1] "PostgreSQL DB versions 8.3 and older are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 2] "PostgreSQL DB versions 8.3 and older are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 3] "PostgreSQL DB versions 8.3 and older are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 4] nil
      "PostgreSQL" [9 0] nil
      "PostgreSQL" [9 1] nil
      "PostgreSQL" [9 2] nil
      "PostgreSQL" [9 3] nil
      "PostgreSQL" [9 4] nil)))

(def not-supported-regex #"PostgreSQL DB versions 8.3 and older are no longer supported")

(deftest test-unsupported-fail
  (testing "unsupported postgres version"
    (let [fail? (atom false)]
      (with-db-version "PostgreSQL" [8 1]
        (fn []
          (pllog/with-log-output log
            (is (re-find not-supported-regex
                         (tu/with-err-str
                           (validate-database-version #(reset! fail? true)))))
            (is (true? @fail?))
            (is (re-find not-supported-regex (last (first @log)))))))))
  (testing "supported postgres version"
    (let [fail? (atom false)]
      (with-db-version "PostgreSQL" [9 3]
        (fn []
          (pllog/with-log-output log
            (is (str/blank?
                 (tu/with-err-str
                   (validate-database-version #(reset! fail? true)))))
            (is (false? @fail?))
            (is (empty? @log))))))))

(deftest test-catalog-schemas
  (is (= (:basic catalogs) (s/validate catalog-schema (:basic catalogs)))))

(deftest test-resource-metadata-diff
  (are [expected left right] (= expected (basic-diff left right))
       {}
       {:type "foo" :title "bar"}
       {:type "foo" :title "bar"}

       {:line       20}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       10
        :tags       #{"file" "class" "foobar"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       20
        :tags       #{"file" "class" "foobar"}}

       {:exported   true
        :file       "/tmp/bar/baz"
        :line       30
        :tags       #{"file" "class" "foobar" "baz"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       20
        :tags       #{"file" "class" "foobar"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   true
        :file       "/tmp/bar/baz"
        :line       30
        :tags       #{"file" "class" "foobar" "baz"}}))

(deftest test-diff-resources-metadata
  (let [resources-1 {{:type "File" :title "/etc/foobar"}
                     {:type       "File"
                      :title      "/etc/foobar"
                      :exported   false
                      :file       "/tmp/foo"
                      :line       10
                      :tags       #{"file" "class" "foobar"}}

                     {:type "File" :title "/etc/foobar/baz"}
                     {:type       "File"
                      :title      "/etc/foobar/baz"
                      :exported   false
                      :file       "/tmp/bar"
                      :line       20
                      :tags       #{"file" "class" "foobar"}}}
        resources-2 {{:type "File" :title "/etc/foobar"}
                     {:type       "File"
                      :title      "/etc/foobar"
                      :exported   false
                      :file       "/tmp/foo"
                      :line       20
                      :tags       #{"file" "class" "foobar"}}

                     {:type "File" :title "/etc/foobar/baz"}
                     {:type       "File"
                      :title      "/etc/foobar/baz"
                      :exported   true
                      :file       "/tmp/bar/baz"
                      :line       30
                      :tags       #{"file" "class" "foobar" "baz"}}}]

    (are [expected left right] (= expected (diff-resources-metadata left right))

         {}
         resources-1
         resources-1

         {{:type "File" :title "/etc/foobar"}
          {:line 30}}
         resources-1
         (assoc-in resources-1 [{:type "File" :title "/etc/foobar"} :line] 30)

         {{:type "File" :title "/etc/foobar"}
          {:line 20}

          {:type "File" :title "/etc/foobar/baz"}
          {:exported   true
           :file       "/tmp/bar/baz"
           :line       30
           :tags       #{"file" "class" "foobar" "baz"}}}
         resources-1
         resources-2)))

(deftest test-merge-resource-hash
  (let [ref->resource {{:type "File" :title "/tmp/foo"}
                       {:line 10}

                       {:type "File" :title "/tmp/bar"}
                       {:line 20}}
        ref->hash {{:type "File" :title "/tmp/foo"}
                   "foo hash"

                   {:type "File" :title "/tmp/bar"}
                   "bar hash"}]
    (is (= {{:type "File" :title "/tmp/foo"}
            {:line 10 :resource "foo hash"}

            {:type "File" :title "/tmp/bar"}
            {:line 20 :resource "bar hash"}}

           (merge-resource-hash ref->hash ref->resource)))))
