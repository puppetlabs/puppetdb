(ns puppetlabs.puppetdb.scf.storage-test
  (:require [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.facts :as facts
             :refer [path->pathmap string-to-factpath value->valuemap]]
            [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
            [puppetlabs.puppetdb.scf.migrate :as migrate]
            [clojure.walk :as walk]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [metrics.histograms :refer [sample histogram]]
            [metrics.counters :as counters]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [clojure.string :as str]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :refer :all]
            [puppetlabs.puppetdb.testutils.events :refer :all]
            [puppetlabs.puppetdb.random :as random]
            [puppetlabs.puppetdb.scf.storage :refer :all]
            [clojure.test :refer :all]
            [clojure.math.combinatorics :refer [combinations subsets]]
            [clj-time.core :refer [ago before? from-now now days]]
            [clj-time.coerce :refer [to-timestamp to-string]]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]))

(def reference-time "2014-10-28T20:26:21.727Z")
(def previous-time "2014-10-26T20:26:21.727Z")

(defn-validated expire-node!
  "Expire the given host, recording expire-time. If the node is
  already expired, no change is made."
  [certname :- String expire-time :- pls/Timestamp]
  (jdbc/do-prepared
   "update certnames set expired = ? where certname=? and expired is null"
   [(to-timestamp expire-time) certname]))

;; When only one db is needed.
(defmacro deftest-db [name & body]
  `(deftest ~name (with-test-db ~@body)))

(deftest-db ensure-producer-test
  (let [prod1 "foo.com"
        prod2 "bar.com"]
    (ensure-producer prod1)
    (testing "doesn't create new row for existing producer"
      (is (= 1 (ensure-producer prod1))))
    (testing "creates new row for non-existing producer"
      (is (= 2 (ensure-producer prod2))))))

(defn-validated factset-map :- {s/Str s/Str}
  "Return all facts and their values for a given certname as a map"
  [certname :- String]
  (let [result (jdbc/query
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
                    WHERE fp.depth = 0 AND fs.certname = ?"
                 certname])]
    (zipmap (map :name result)
            (map :value result))))

(deftest-db large-fact-update
  (testing "updating lots of facts"
    (let [certname "scale.com"
          facts1 (zipmap (take 10000 (repeatedly #(random/random-string 10)))
                         (take 10000 (repeatedly #(random/random-string 10))))
          timestamp1 (-> 2 days ago)
          facts2 (zipmap (take 11000 (repeatedly #(random/random-string 10)))
                         (take 11000 (repeatedly #(random/random-string 10))))
          timestamp2 (-> 1 days ago)
          producer "bar.com"]
      (add-certname! certname)
      (add-facts! {:certname certname
                   :values facts1
                   :timestamp timestamp1
                   :environment nil
                   :producer_timestamp timestamp1
                   :producer producer})

      (testing "10000 facts stored"
        (is (= 10000
               (->> (query-to-vec "SELECT count(*) as c from fact_values")
                    first
                    :c))))

      (update-facts! {:certname certname
                      :values facts2
                      :timestamp timestamp2
                      :environment nil
                      :producer_timestamp timestamp2
                      :producer producer})

      (testing "11000 facts stored"
        (is (= 11000
               (->> (query-to-vec "SELECT count(*) as c from fact_values")
                    first
                    :c)))))))

(deftest-db escaped-string-factnames
  (testing "should work with escaped strings"
    (let [certname "some_certname"
          facts {"\"hello\"" "world"
                 "foo#~bar" "baz"
                 "\"foo" "bar"
                 "foo#~" "bar"
                 "foo" "bar"}
          producer "bar.com"]
      (add-certname! certname)

      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment nil
                   :producer_timestamp previous-time
                   :producer producer})
      (let [stored-names (->> (jdbc/query ["SELECT name from fact_paths"])
                              (map :name)
                              set)]
        (is (= stored-names (set (keys facts))))))))

(deftest-db fact-persistence
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}
          producer "bar.com"]
      (add-certname! certname)

      (is (nil?
           (jdbc/with-db-transaction []
             (timestamp-of-newest-record :factsets "some_certname"))))
      (is (empty? (factset-map "some_certname")))

      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment nil
                   :producer_timestamp previous-time
                   :producer producer})
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

        (is (jdbc/with-db-transaction []
              (timestamp-of-newest-record :factsets  "some_certname")))
        (is (= facts (factset-map "some_certname"))))

      (testing "should add the certname if necessary"
        (is (= (query-to-vec "SELECT certname FROM certnames")
               [{:certname certname}])))
      (testing "replacing facts"
        ;;Ensuring here that new records are inserted, updated
        ;;facts are updated (not deleted and inserted) and that
        ;;the necessary deletes happen
        (tu/with-wrapped-fn-args [adds sql/insert!
                                  updates sql/update!]
          (let [new-facts {"domain" "mynewdomain.com"
                           "fqdn" "myhost.mynewdomain.com"
                           "hostname" "myhost"
                           "kernel" "Linux"
                           "uptime_seconds" "3600"}]
            (replace-facts! {:certname certname
                             :values new-facts
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :timestamp reference-time
                             :producer producer})
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
            (testing "producer_timestamp should store current time"
              (is (= (query-to-vec "SELECT producer_timestamp FROM factsets")
                     [{:producer_timestamp (to-timestamp reference-time)}])))
            (testing "should update existing keys"
              (is (some #{{:timestamp (to-timestamp reference-time)
                           :environment_id 1
                           :hash "cf56e1d01b3517d26f875855da4459ce19f8cd18"
                           :producer_timestamp (to-timestamp reference-time)}}
                        ;; Again we grab the pertinent non-id bits
                        (map (fn [itm]
                               (-> (second itm)
                                   (update-in [:hash] sutils/parse-db-hash)))
                             (map rest @updates))))
              (is (some (fn [update-call]
                          (and (= :factsets (first update-call))
                               (:timestamp (second update-call))))
                        (map rest @updates))))
            (testing "should only insert uptime_seconds"
              (is (some #{[:fact_paths {:path "uptime_seconds"
                                        :name "uptime_seconds"
                                        :depth 0}]}
                        (map rest @adds)))))))

      (testing "replacing all new facts"
        (delete-certname-facts! certname)
        (replace-facts! {:certname certname
                         :values facts
                         :environment "DEV"
                         :producer_timestamp (now)
                         :timestamp (now)
                         :producer producer})
        (is (= facts (factset-map "some_certname"))))

      (testing "replacing all facts with new ones"
        (delete-certname-facts! certname)
        (add-facts! {:certname certname
                     :values facts
                     :timestamp previous-time
                     :environment nil
                     :producer_timestamp previous-time
                     :producer nil})
        (replace-facts! {:certname certname
                         :values {"foo" "bar"}
                         :environment "DEV"
                         :producer_timestamp (now)
                         :timestamp (now)
                         :producer producer})
        (is (= {"foo" "bar"} (factset-map "some_certname"))))

      (testing "replace-facts with only additions"
        (let [fact-map (factset-map "some_certname")]
          (replace-facts! {:certname certname
                           :values (assoc fact-map "one more" "here")
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (is (= (assoc fact-map  "one more" "here")
                 (factset-map "some_certname")))))

      (testing "replace-facts with no change"
        (let [fact-map (factset-map "some_certname")]
          (replace-facts! {:certname certname
                           :values fact-map
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (is (= fact-map
                 (factset-map "some_certname")))))
      (testing "stable hash when no facts change"
        (let [fact-map (factset-map "some_certname")
              {old-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
          (replace-facts! {:certname certname
                           :values fact-map
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (let [{new-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
            (is (= old-hash new-hash)))
          (replace-facts! {:certname certname
                           :values (assoc fact-map "another thing" "goes here")
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (let [{new-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
            (is (not= old-hash new-hash))))))))

(deftest-db fact-persistance-with-environment
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}
          producer "bar.com"]
      (add-certname! certname)

      (is (nil?
           (jdbc/with-db-transaction []
            (timestamp-of-newest-record :factsets "some_certname"))))
      (is (empty? (factset-map "some_certname")))
      (is (nil? (environment-id "PROD")))

      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment "PROD"
                   :producer_timestamp previous-time
                   :producer producer})

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
       {:certname certname
        :values facts
        :timestamp (-> 1 days ago)
        :environment "DEV"
        :producer_timestamp (-> 1 days ago)
        :producer producer})

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
            {:certname c :values v
             :environment nil :timestamp (now) :producer_timestamp (now) :producer nil})
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
      (with-test-db
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
          (replace-facts! (facts-now "c-y" {})))))
    (testing "during replace, when value is only referred to by the same factset"
      (with-test-db
        (let [facts {"a" "1" "b" "1"}]
          (add-certname! "c-x")
          (replace-facts! (facts-now "c-x" facts))
          (replace-facts! (facts-now "c-x" (assoc facts "b" "2"))))))
    (testing "during replace - values only in one factset, and all paths change"
      (with-test-db
        (let [facts {"a" "1" "b" "2"}
              facts-swapped {"a" "2" "b" "1"}]
          (add-certname! "c-x")
          (replace-facts! (facts-now "c-x" facts))
          (replace-facts! (facts-now "c-x" facts-swapped)))))
    (testing "paths - globally, incrementally"
      (with-test-db
        (letfn [(str->pathmap [s] (-> s string-to-factpath path->pathmap))]
          (jdbc/insert! :fact_paths (str->pathmap "foo"))
          (delete-orphaned-paths! 0)
          (is (= (map #(dissoc % :id) (db-paths)) [(str->pathmap "foo")]))
          (delete-orphaned-paths! 1)
          (is (empty? (map #(dissoc % :id) (db-paths))))
          (jdbc/insert! :fact_paths (str->pathmap "foo"))
          (delete-orphaned-paths! 11)
          (is (empty? (map #(dissoc % :id) (db-paths))))
          (apply jdbc/insert!
                 :fact_paths
                 (for [x (range 10)] (str->pathmap (str "foo-" x))))
          (delete-orphaned-paths! 3)
          (is (= 7 (:c (first
                        (query-to-vec
                         "select count(id) as c from fact_paths"))))))))
    (testing "values - globally, incrementally"
      (with-test-db
        (jdbc/insert! :fact_values
                      (update-in (value->valuemap "foo")
                                 [:value_hash] sutils/munge-hash-for-storage))
        (delete-orphaned-values! 0)
        (is (= (db-vals) #{"foo"}))
        (delete-orphaned-values! 1)
        (is (empty? (db-vals)))
        (jdbc/insert! :fact_values
                      (update-in (value->valuemap "foo")
                                 [:value_hash] sutils/munge-hash-for-storage))
        (delete-orphaned-values! 11)
        (is (empty? (db-vals)))
        (apply jdbc/insert!
               :fact_values
               (for [x (range 10)] (update-in (value->valuemap (str "foo-" x))
                                              [:value_hash]
                                              sutils/munge-hash-for-storage)))
        (delete-orphaned-values! 3)
        (is (= 7 (:c (first
                      (query-to-vec
                       "select count(id) as c from fact_values")))))))))

(def catalog (:basic catalogs))
(def certname (:certname catalog))
(def current-time (str (now)))

(defmacro with-historical-catalogs-enabled [limit & body]
  `(let [orig-limit# @historical-catalogs-limit
         orig-jsonb-setting# @store-catalogs-jsonb-columns?]
     (try
       (reset! historical-catalogs-limit ~limit)
       (reset! store-catalogs-jsonb-columns? true)
       ~@body
       (finally
         (reset! historical-catalogs-limit orig-limit#)
         (reset! store-catalogs-jsonb-columns? orig-jsonb-setting#)))))

(deftest-db historical-catalogs-storage-test
  (with-historical-catalogs-enabled 3

    (add-certname! "basic.catalogs.com")

    (testing "stores JSONB resources and edges fields"
      (store-catalog! (assoc catalog :producer_timestamp (-> 2 days ago)) (now))
      (is (= [{:count 1}]
             (query-to-vec [(str "SELECT COUNT(*) FROM catalogs WHERE resources IS NOT NULL"
                                 " AND edges IS NOT NULL")])))
      (is (= #{{:source_type "Class" :source_title "foobar"
                :target_type "File" :target_title "/etc/foobar"
                :relationship "contains"}
               {:source_type "Class" :source_title "foobar"
                :target_type "File" :target_title "/etc/foobar/baz"
                :relationship "contains"}
               {:source_type "File" :source_title "/etc/foobar"
                :target_type "File" :target_title "/etc/foobar/baz"
                :relationship "required-by"}}
             (->> (query-to-vec [(str "SELECT edges FROM catalogs")])
                  (mapcat (comp sutils/parse-db-json :edges))
                  set)))
      (is (= #{{:type "Class" :title "foobar" :exported false
                :tags #{"class" "foobar"} :file nil :line nil :parameters {}}
               {:type "File" :title "/etc/foobar" :exported false
                :file "/tmp/foo" :line 10 :tags #{"file" "class" "foobar"}
                :parameters {:ensure "directory" :group "root" :user "root"}}
               {:type "File" :title "/etc/foobar/baz" :exported false
                :file "/tmp/bar" :line 20 :tags #{"file" "class" "foobar"}
                :parameters {:ensure "directory" :group "root" :user "root"
                             :require "File[/etc/foobar]"}}}
             (->> (query-to-vec [(str "SELECT resources FROM catalogs")])
                  (mapcat (comp sutils/parse-db-json :resources))
                  (map #(update % :tags set))
                  set))))

    (testing "stores a second catalog"
      (store-catalog! (assoc catalog :producer_timestamp current-time) (now))
      (is (= [{:count 2}]
             (query-to-vec ["SELECT COUNT(*) FROM catalogs"]))))

    (testing "storing an older catalog doesn't change the latest id"
      (store-catalog! (assoc catalog :producer_timestamp (-> 1 days ago)) (now))
      (is (= [{:count 3}]
             (query-to-vec ["SELECT COUNT(*) FROM catalogs"])))

      (is (= [{:producer_timestamp (to-timestamp current-time)}]
             (query-to-vec [(str "SELECT catalogs.producer_timestamp FROM certnames"
                                 " JOIN latest_catalogs ON certnames.id = latest_catalogs.certname_id"
                                 " JOIN catalogs ON catalogs.id = latest_catalogs.catalog_id"
                                 " WHERE certnames.certname = 'basic.catalogs.com'")]))))

    (testing "only stores up to three catalogs a certname"
      (store-catalog! (assoc catalog :producer_timestamp (-> 3 days ago)) (now))
      (store-catalog! (assoc catalog :producer_timestamp (-> 4 days ago)) (now))
      (store-catalog! (assoc catalog :producer_timestamp (-> 5 days ago)) (now))
      (store-catalog! (assoc catalog :producer_timestamp (-> 6 days ago)) (now))
      (is (= [{:count 3}] (query-to-vec ["SELECT COUNT(*) FROM catalogs"]))))

    (testing "storing a new certname doesn't delete other certname's catalogs"
      (add-certname! "bar.bazz.com")
      (store-catalog! (assoc catalog
                             :certname "bar.bazz.com"
                             :producer_timestamp (-> 1 days ago)) (now))
      (is (= [{:count 4}] (query-to-vec ["SELECT COUNT(*) FROM catalogs"]))))))

(deftest-db catalog-persistence
  (testing "Persisted catalogs"
    (add-certname! certname)
    (replace-catalog! (assoc catalog :producer_timestamp current-time))

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
                                 FROM catalog_resources cr, certnames c
                                 WHERE c.id=cr.certname_id
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
                 [{:type "Class" :title "foobar" :tags ["class" "foobar"] :exported false :file nil :line nil}
                  {:type "File" :title "/etc/foobar" :tags ["class" "file" "foobar"] :exported false :file "/tmp/foo" :line 10}
                  {:type "File" :title "/etc/foobar/baz" :tags ["class" "file" "foobar"] :exported false :file "/tmp/bar" :line 20}])))))))

(deftest-db catalog-persistence-with-environment
  (let [other-certname "notbasic.catalogs.com"]
    (testing "Persisted catalogs"
      (add-certname! certname)
      (add-certname! other-certname)

      (is (nil? (environment-id "PROD")))

      (replace-catalog! (assoc catalog :environment "PROD"))

      (testing "should persist environment if the environment is new"
        (let [id (environment-id "PROD")]
          (is (number? (environment-id "PROD")))
          (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                 (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

          (testing "Adding another catalog with the same environment should just use the existing environment"
            (replace-catalog! (assoc catalog :environment "PROD" :certname other-certname))

            (is (= [{:certname other-certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                   (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs where certname=?" other-certname])))))))))

(deftest-db updating-catalog-environment
  (testing "should persist environment if the environment is new"
    (let [prod-id (ensure-environment "PROD")
          dev-id (ensure-environment "DEV")]

      (add-certname! certname)
      (replace-catalog! (assoc catalog :environment "DEV"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id dev-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

      (replace-catalog! (assoc catalog :environment "PROD"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id prod-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"]))))))

(deftest-db catalog-replacement
  (testing "should noop if replaced by themselves"
    (add-certname! certname)
    (let [hash (replace-catalog! catalog)]
      (replace-catalog! catalog (now))

      (is (= (query-to-vec ["SELECT certname FROM certnames"])
             [{:certname certname}]))

      (is (= (query-to-vec [(format "SELECT %s AS hash FROM catalogs" (sutils/sql-hash-as-str "hash"))])
             [{:hash hash}])))))

(deftest-db edge-replacement-differential
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
               {["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                 "57495b553981551c5194a21b9a26554cd93db3d9"
                 "contains"] nil,
                 ["57495b553981551c5194a21b9a26554cd93db3d9"
                  "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                  "required-by"] nil,
                  ["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                   "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                   "contains"] nil})))

      ;; Lets intercept the insert/update/delete level so we can test it later
      ;; Here we only replace edges, so we can capture those specific SQL
      ;; operations
      (tu/with-wrapped-fn-args [adds sql/insert!
                                deletes sql/delete!]
        (let [resources    (:resources modified-catalog)
              refs-to-hash (reduce-kv (fn [i k v]
                                        (assoc i k (shash/resource-identity-hash v)))
                                      {} resources)]
          (replace-edges! certname modified-edges refs-to-hash)
          (testing "ensure catalog-edges-map returns a predictable value"
            (is (= (catalog-edges-map certname)
                   {["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                     "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                     "contains"] nil,
                     ["57495b553981551c5194a21b9a26554cd93db3d9"
                      "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                      "required-by"] nil
                      ["57495b553981551c5194a21b9a26554cd93db3d9"
                       "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                       "before"] nil})))

          (testing "should only delete the 1 edge"
            (let [source-hash "ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                  target-hash "57495b553981551c5194a21b9a26554cd93db3d9"]
              (is (= [[:edges [(str "certname=?"
                                    " and source=?::bytea"
                                    " and target=?::bytea"
                                    " and type=?")
                               "basic.catalogs.com"
                               (sutils/bytea-escape source-hash)
                               (sutils/bytea-escape target-hash)
                               "contains"]]]
                     (map rest @deletes)))))
          (testing "should only insert the 1 edge"
            (is (= [[:edges {:certname "basic.catalogs.com"
                             :source (sutils/munge-hash-for-storage "57495b553981551c5194a21b9a26554cd93db3d9")
                             :target (sutils/munge-hash-for-storage "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1")
                             :type "before"}]]
                   (map rest @adds))))
          (testing "when reran to check for idempotency"
            (reset! adds [])
            (reset! deletes [])
            (replace-edges! certname modified-edges refs-to-hash)
            (testing "should delete no edges"
              (is (empty? @deletes)))
            (testing "should insert no edges"
              (is (empty?@adds)))))))))

(deftest-db catalog-duplicates
  (testing "should share structure when duplicate catalogs are detected for the same host"
    (add-certname! certname)
    (let [hash (replace-catalog! catalog)
          prev-dupe-num (counters/value (:duplicate-catalog performance-metrics))
          prev-new-num  (counters/value (:updated-catalog performance-metrics))]

      ;; Do an initial replacement with the same catalog
      (replace-catalog! catalog (now))
      (is (= 1 (- (counters/value (:duplicate-catalog performance-metrics)) prev-dupe-num)))
      (is (= 0 (- (counters/value (:updated-catalog performance-metrics)) prev-new-num)))

      ;; Store a second catalog, with the same content save the version
      (replace-catalog! (assoc catalog :version "abc123") (now))
      (is (= 2 (- (counters/value (:duplicate-catalog performance-metrics)) prev-dupe-num)))
      (is (= 0 (- (counters/value (:updated-catalog performance-metrics)) prev-new-num)))

      (is (= (query-to-vec ["SELECT certname FROM certnames"])
             [{:certname certname}]))

      (is (= (query-to-vec [(format "SELECT certname, %s AS hash FROM catalogs" (sutils/sql-hash-as-str "hash"))])
             [{:hash hash
               :certname certname}]))

      (replace-catalog! (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :line] 20) (now))
      (is (= 2 (- (counters/value (:duplicate-catalog performance-metrics)) prev-dupe-num)))
      (is (= 1 (- (counters/value (:updated-catalog performance-metrics)) prev-new-num))))))

(deftest-db fact-delete-should-prune-paths-and-values
  (add-certname! certname)

  ;; Add some facts
  (let [facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"
               "networking" {"eth0" {"ipaddresses" ["192.168.0.11"]}}}]
    (add-facts! {:certname certname
                 :values facts
                 :timestamp (-> 2 days ago)
                 :environment "ENV3"
                 :producer_timestamp (-> 2 days ago)
                 :producer "bar.com"}))
  (let [factset-id (:id (first (query-to-vec ["SELECT id from factsets"])))
        fact-value-ids (set (map :id (query-to-vec ["SELECT id from fact_values"])))]

    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_values"]))) 7))
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_paths"]))) 7))
    (delete-certname-facts! certname)
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_values"]))) 0))
    (is (= (:c (first (query-to-vec ["SELECT count(id) as c FROM fact_paths"]))) 0))))

(deftest-db catalog-bad-input
  (testing "should noop"
    (testing "on bad input"
      (is (thrown? clojure.lang.ExceptionInfo (replace-catalog! {})))

      ;; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
             [{:nrows 0}])))))

(defn foobar->foobar2 [x]
  (if (and (string? x) (= x "/etc/foobar"))
    "/etc/foobar2"
    x))

(defn table-args
  "Many of the java.jdbc functions accept a table name as the second arg, this
   function grabs that argument"
  [coll]
  (map second coll))

(defn remove-edge-changes
  "Remove the edge related changes from the `coll` of function call arguments"
  [coll]
  (remove #(= :edges (first %)) coll))

(defn sort= [& args]
  (apply = (map sort args)))

(deftest-db existing-catalog-update
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)]

    (testing "inserting new catalog with resources"

      (add-certname! certname)
      (is (empty? (query-to-vec "SELECT * from catalogs where certname=?" certname)))

      (replace-catalog! catalog old-date)

      (let [results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first results)]

        (is (= 1 (count results)))
        (is (= (to-timestamp old-date) (to-timestamp timestamp)))))

    (testing "changing a resource title"
      (let [[{orig-id :id
              orig-tx-id :transaction_uuid
              orig-timestamp :timestamp}]
            (query-to-vec (str "select id, timestamp, transaction_uuid::text"
                               "  from catalogs where certname=?")
                          certname)
            updated-catalog (walk/prewalk foobar->foobar2 (:basic catalogs))
            new-uuid (kitchensink/uuid)
            metrics-map performance-metrics]

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c
                                   INNER JOIN latest_catalogs ON latest_catalogs.catalog_id = c.id
                                   INNER JOIN catalog_resources cr ON latest_catalogs.certname_id = cr.certname_id
                                   WHERE c.certname=?" certname))))

        (tu/with-wrapped-fn-args [inserts sql/insert!
                                  deletes sql/delete!
                                  updates sql/update!]
          (with-redefs [performance-metrics
                        (assoc metrics-map
                               :catalog-volatility (histogram storage-metrics-registry [(str (gensym))]))]
            (replace-catalog! (assoc updated-catalog :transaction_uuid new-uuid) yesterday)

            ;; 2 edge deletes
            ;; 2 edge inserts
            ;; 1 params insert
            ;; 1 params cache insert
            ;; 1 catalog_resource insert
            ;; 1 catalog_resource delete
            (is (= 8 (apply + (sample (:catalog-volatility performance-metrics))))))

          (is (sort= [:resource_params_cache :resource_params :catalog_resources :edges]
                     (table-args @inserts)))
          (is (= [:catalogs]
                 (table-args @updates)))
          (is (= [[:catalog_resources ["certname_id = ? and type = ? and title = ?"
                                       (-> @updates first (#(nth % 3)) second)
                                       "File" "/etc/foobar"]]]
                 (remove-edge-changes (map rest @deletes)))))

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar2"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c
                                   INNER JOIN certnames ON certnames.certname = c.certname
                                   INNER JOIN catalog_resources cr ON cr.certname_id = certnames.id
                                   WHERE c.certname=?" certname))))

        (let [results (query-to-vec
                       (str "select id, timestamp, transaction_uuid::text"
                            "  from catalogs where certname=?")
                       certname)
              {new-timestamp :timestamp
               new-tx-id :transaction_uuid
               new-id :id} (first results)]

          (is (= 1 (count results)))
          (is (= (to-timestamp yesterday) (to-timestamp new-timestamp)))
          (is (= new-tx-id new-uuid))
          (is (= orig-id new-id))
          (is (not= orig-tx-id new-tx-id))
          (is (not= orig-timestamp new-timestamp)))))))

(deftest-db add-resource-to-existing-catalog
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)]
    (add-certname! certname)
    (replace-catalog! catalog old-date)

    (is (= 3 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))

    (tu/with-wrapped-fn-args [inserts sql/insert!
                              updates sql/update!
                              deletes sql/delete!]
      (replace-catalog! (assoc-in catalog
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
                    old-date)

      (is (sort= [:resource_params_cache :resource_params :catalog_resources]
                 (table-args @inserts)))
      (is (= [:catalogs] (table-args @updates)))
      (is (empty? @deletes)))

    (is (= 4 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))))

(deftest-db change-line-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:line nil}
             {:line 10}
             {:line 20}}
           (set (query-to-vec "SELECT line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :line 1000) resources))))

    (is (= [{:line 1000}
            {:line 1000}
            {:line 1000}]
           (query-to-vec "SELECT line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))

(deftest-db change-exported-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing exported"
    (is (= #{{:exported false
              :title "foobar"}
             {:exported false
              :title "/etc/foobar"}
             {:exported false
              :title "/etc/foobar/baz"}}
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
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
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))))

(deftest-db change-file-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:title "foobar"
              :file nil}
             {:title "/etc/foobar"
              :file "/tmp/foo"}
             {:title "/etc/foobar/baz"
              :file "/tmp/bar"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :file "/tmp/foo.pp") resources))))

    (is (= #{{:title "foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar/baz"
              :file "/tmp/foo.pp"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))))

(defn tags->set
  "Converts tags from a vector to a set"
  [result-set]
  (mapv (fn [result]
          (update-in result [:tags] set))
        result-set))

(deftest-db change-tags-on-resource
  (add-certname! certname)
  (replace-catalog! catalog)

  (is (= #{{:title "foobar"
            :tags #{"class" "foobar"}
            :line nil}
           {:title "/etc/foobar"
            :tags #{"file" "class" "foobar"}
            :line 10}
           {:title "/etc/foobar/baz"
            :tags #{"file" "class" "foobar"}
            :line 20}}
         (-> (query-to-vec "SELECT title, tags, line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)
             jdbc/convert-result-arrays
             tags->set
             set)))

  (replace-catalog! (update-in catalog [:resources]
                           (fn [resources]
                             (-> resources
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :tags] #{"totally" "different" "tags"})
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :line] 500)))))
  (is (= #{{:title "foobar"
            :tags #{"class" "foobar"}
            :line nil}
           {:title "/etc/foobar"
            :line 500
            :tags #{"totally" "different" "tags"}}
           {:title "/etc/foobar/baz"
            :line 20
            :tags #{"file" "class" "foobar"}}}
         (-> (query-to-vec "SELECT title, tags, line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)
             jdbc/convert-result-arrays
             tags->set
             set))))

(deftest-db removing-resources
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)
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
    (replace-catalog! catalog-with-extra-resource old-date)

    (let [certname-id (:id (first (query-to-vec "SELECT id from certnames where certname=?" certname)))]
      (is (= 4 (count (query-to-vec "SELECT * from catalog_resources where certname_id = ?" certname-id))))

      (tu/with-wrapped-fn-args [inserts sql/insert!
                                updates sql/update!
                                deletes sql/delete!]

        (replace-catalog! catalog yesterday)
        (is (empty? @inserts))
        (is (= [:catalogs] (table-args @updates)))
        (is (= [:catalog_resources] (table-args @deletes))))

      (let [catalog-results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first catalog-results)
            resources (set (query-to-vec "SELECT type, title from catalog_resources where certname_id = ?" certname-id))]

        (is (= 1 (count catalog-results)))
        (is (= 3 (count resources)))
        (is (= (set (keys (:resources catalog)))
               resources))
        (is (= (to-timestamp yesterday) (to-timestamp timestamp)))))))

(defn foobar-params []
  (jdbc/query-with-resultset
   ["SELECT p.name AS k, p.value AS v
       FROM catalog_resources cr, certnames c, resource_params p
       WHERE cr.certname_id = c.id AND cr.resource = p.resource AND c.certname=?
         AND cr.type=? AND cr.title=?"
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   (fn [rs]
     (reduce (fn [acc row]
               (assoc acc (keyword (:k row))
                      (json/parse-string (:v row))))
             {}
             (sql/result-set-seq rs)))))

(defn foobar-params-cache []
  (jdbc/query-with-resultset
   ["SELECT rpc.parameters as params
       FROM catalog_resources cr, certnames c, resource_params_cache rpc
       WHERE cr.certname_id = c.id AND cr.resource = rpc.resource AND c.certname=?
         AND cr.type=? AND cr.title=?"
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   #(-> (sql/result-set-seq %)
        first
        :params
        sutils/parse-db-json)))

(defn foobar-param-hash []
  (jdbc/query-with-resultset
   [(format "SELECT %s AS hash
               FROM catalog_resources cr, certnames c
               WHERE cr.certname_id = c.id AND c.certname=? AND cr.type=?
                 AND cr.title=?"
            (sutils/sql-hash-as-str "cr.resource"))
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   (comp :hash first sql/result-set-seq)))

(deftest-db catalog-resource-parameter-changes
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)]
    (add-certname! certname)
    (replace-catalog! catalog old-date)

    (let [orig-resource-hash (foobar-param-hash)
          add-param-catalog (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters :uid] "100")]
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))

      (tu/with-wrapped-fn-args [inserts sql/insert!
                                updates sql/update!
                                deletes sql/delete!]

        (replace-catalog! add-param-catalog yesterday)
        (is (sort= [:catalogs :catalog_resources]
                   (table-args @updates)))

        (is (empty? (remove-edge-changes (map rest @deletes))))

        (is (sort= [:resource_params_cache :resource_params :edges]
                   (table-args @inserts))))

      (is (not= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))
      (tu/with-wrapped-fn-args [inserts sql/insert!
                                updates sql/update!
                                deletes sql/delete!]
        (replace-catalog! catalog old-date)

        (is (empty? (remove #(= :edges (first %)) (map rest @inserts))))
        (is (empty? (remove #(= :edges (first %)) (map rest @deletes))))
        (is (= (sort [:catalog_resources :catalogs])
               (sort (table-args @updates)))))

      (is (= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache))))))

(deftest-db catalog-referential-integrity-violation
  (testing "on input that violates referential integrity"
    ;; This catalog has an edge that points to a non-existant resource
    (let [catalog (:invalid catalogs)]
      (is (thrown? clojure.lang.ExceptionInfo (replace-catalog! catalog)))

      ;; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
             [{:nrows 0}])))))

(deftest-db node-deactivation
  (let [certname        "foo.example.com"
        query-certnames #(query-to-vec ["select certname, deactivated from certnames"])
        deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]
    (add-certname! certname)

    (testing "deactivating a node"
      (testing "should mark the node as deactivated"
        (deactivate-node! certname)
        (let [result (first (query-certnames))]
          (is (= certname (:certname result)))
          (is (deactivated? result))))

      (testing "should not change the node if it's already inactive"
        (let [original (query-certnames)]
          (deactivate-node! certname)
          ;; Convert any :deactivated values to #t for comparison
          ;; since we only care about the state.
          (letfn [(deactivated->truthy [x]
                    (assoc x :deactivated (when (:deactivated x) true)))]
            (is (= (map deactivated->truthy original)
                   (map deactivated->truthy (query-certnames))))))))

    (testing "activating a node"
      (testing "should activate the node if it was inactive"
        (activate-node! certname)
        (is (= (query-certnames) [{:certname certname :deactivated nil}])))

      (testing "should do nothing if the node is already active"
        (let [original (query-certnames)]
          (activate-node! certname)
          (is (= original (query-certnames))))))

    (testing "auto-reactivated based on a command"
      (let [before-deactivating (to-timestamp (-> 1 days ago))
            after-deactivating  (to-timestamp (-> 1 days from-now))]
        (testing "should activate the node if the command happened after it was deactivated"
          (deactivate-node! certname)
          (is (= true (maybe-activate-node! certname after-deactivating)))
          (is (= (query-certnames) [{:certname certname :deactivated nil}])))

        (testing "should not activate the node if the command happened before it was deactivated"
          (deactivate-node! certname)
          (is (= false (maybe-activate-node! certname before-deactivating)))
          (let [result (first (query-certnames))]
            (is (= certname (:certname result)))
            (is (deactivated? result))))

        (testing "should do nothing if the node is already active"
          (activate-node! certname)
          (is (= false (maybe-activate-node! certname (now))))
          (is (= (query-certnames) [{:certname certname :deactivated nil}])))))))

(deftest-db fresh-node-not-expired
  (testing "fresh nodes are not expired"
    (let [catalog (:empty catalogs)
          certname (:certname catalog)]
      (add-certname! certname)
      (replace-catalog! (assoc catalog :producer_timestamp (now)) (now))
      (is (= [] (expire-stale-nodes (-> 3 days .toPeriod))))
      (is (= (map :certname (query-to-vec "select certname from certnames"))
             [certname])))))

(deftest expire-nodes-with-stale-catalogs-and-facts-or-none
  (testing "nodes with only stale facts/catalogs or no facts/catalogs expire"
    (let [mutators {:rc #(replace-catalog!
                          (assoc (:empty catalogs) :certname "node1")
                          (-> 2 days ago))
                    :rf #(replace-facts!
                          {:certname "node1"
                           :values {"foo" "bar"}
                           :environment "DEV"
                           :producer_timestamp (-> 10 days ago)
                           :timestamp (-> 2 days ago)
                           :producer "baz.com"})}]
      (doseq [ops (subsets (keys mutators))]
        (with-test-db
          (add-certname! "node1")
          (dorun (map #((mutators %)) ops))
          (is (= [ops ["node1"]]
                 [ops (expire-stale-nodes (-> 1 days .toPeriod))])))))))

(deftest-db node-with-only-fresh-report-is-not-expired
  (testing "does not expire a node with a recent report and nothing else"
    (let [report (-> (:basic reports)
                     (assoc :environment "ENV2")
                     (assoc :end_time (now))
                     (assoc :producer_timestamp (now)))]
      (store-example-report! report (now))
      (is (= [] (expire-stale-nodes (-> 1 days .toPeriod)))))))

(deftest stale-nodes-expiration-via-reports
  (let [report-at #(assoc (:basic reports)
                          :environment "ENV2"
                          :end_time %
                          :producer_timestamp %)
        stamp (now)
        stale-stamp-1 (-> 2 days ago)
        stale-stamp-2 (-> 3 days ago)]
    (with-test-db
      (testing "doesn't return node with a recent report and nothing else"
        (store-example-report! (report-at stamp) stamp)
        (is (= []  (expire-stale-nodes (-> 1 days .toPeriod)))))
      (testing "doesn't return node with a recent report and a stale report"
        (store-example-report! (report-at stale-stamp-1) stale-stamp-1)
        (is (= []  (expire-stale-nodes (-> 1 days .toPeriod))))))
    (with-test-db
      (testing "returns a node with only stale reports"
        (store-example-report! (report-at stale-stamp-1) stale-stamp-1)
        (store-example-report! (report-at stale-stamp-2) stale-stamp-2)
        (is (= ["foo.local"] (expire-stale-nodes (-> 1 days .toPeriod))))))))

(deftest-db stale-nodes-expiration-via-catalogs
  (let [repcat (fn [type stamp]
                 (replace-catalog! (assoc (type catalogs)
                                          :certname "node1"
                                          :producer_timestamp stamp)
                                   stamp))
        stamp (now)
        stale-stamp (-> 2 days ago)]
    (with-test-db
      (testing "doesn't return node with a recent catalog and nothing else"
        (add-certname! "node1")
        (repcat :empty stamp)
        (is (= [] (expire-stale-nodes (-> 1 days .toPeriod))))))
    (with-test-db
      (testing "returns a node with only a stale catalog"
        (add-certname! "node1")
        (repcat :empty stale-stamp)
        (is (= ["node1"] (expire-stale-nodes (-> 1 days .toPeriod)))))))

  (with-historical-catalogs-enabled 3
    (let [history-limit @historical-catalogs-limit
          addcat (fn [type stamp]
                   (add-catalog! (assoc (type catalogs)
                                        :certname "node1"
                                        :producer_timestamp stamp)
                                 stamp
                                 history-limit))
          stamp (now)
          stale-stamp (-> 2 days ago)]
      (testing "with historical"
        (with-test-db
          (testing "doesn't return node with a recent catalog and nothing else"
            (add-certname! "node1")
            (addcat :empty stamp)
            (is (= [] (expire-stale-nodes (-> 1 days .toPeriod))))))
        (with-test-db
          (testing "returns a node with only a stale catalog"
            (add-certname! "node1")
            (addcat :empty stale-stamp)
            (is (= ["node1"] (expire-stale-nodes (-> 1 days .toPeriod))))))
        (with-test-db
          (testing "doesn't return node with a recent report and a stale report"
            (add-certname! "node1")
            (addcat :empty stale-stamp)
            (addcat :basic stamp)
            (is (=  [] (expire-stale-nodes (-> 1 days .toPeriod))))))))))

(deftest-db only-nodes-older-than-max-age-expired
  (testing "should only return nodes older than max age, and leave others alone"
    (let [catalog (:empty catalogs)]
      (add-certname! "node1")
      (add-certname! "node2")
      (replace-catalog! (assoc catalog
                               :certname "node1"
                               :producer_timestamp (-> 2 days ago))
                        (now))
      (replace-catalog! (assoc catalog
                               :certname "node2"
                               :producer_timestamp (now))
                        (now))
      (is (= ["node1"] (expire-stale-nodes (-> 1 days .toPeriod)))))))

(deftest-db node-purge
  (testing "should purge nodes which were deactivated before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (deactivate-node! "node1")
    (deactivate-node! "node2" (-> 10 days ago))
    (purge-deactivated-and-expired-nodes! (-> 5 days ago))
    (is (= (map :certname
                (query-to-vec
                 "select certname from certnames order by certname asc"))
           ["node1" "node3"]))))

(deftest-db purge-expired-nodes
  (testing "should purge nodes which were expired before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (expire-node! "node1" (now))
    (expire-node! "node2" (-> 10 days ago))
    (purge-deactivated-and-expired-nodes! (-> 5 days ago))
    (is (= (map :certname
                (query-to-vec
                 "select certname from certnames order by certname asc"))
           ["node1" "node3"]))))

(deftest-db report-sweep-nullifies-latest-report
  (testing "ensure that if the latest report is swept, latest_report_id is updated to nil"
    (let [report1 (assoc (:basic reports) :end_time (-> 12 days ago))
          report2 (assoc (:basic reports) :certname "bar.local" :end_time (now) :producer_timestamp (now))]
      (add-certname! "foo.local")
      (add-certname! "bar.local")
      (store-example-report! report1 (-> 12 days ago))
      (store-example-report! report2 (now))
      (let [ids (map :latest_report_id (query-to-vec "select latest_report_id from certnames order by certname"))
            _ (delete-reports-older-than! (-> 11 days ago))
            ids2 (map :latest_report_id (query-to-vec "select latest_report_id from certnames order by certname"))]
        (is (= ids2 [(first ids) nil]))))))

;; Report tests

(defn update-event-timestamps
  "Changes each timestamp in the `report`'s resource_events to `new-timestamp`"
  [report new-timestamp]
  (update-in report [:resource_events]
             (fn [events]
               (map #(assoc % :timestamp new-timestamp) events))))

(let [timestamp (now)
      {:keys [certname] :as report} (:basic reports)
      report-hash (-> report
                      report/report-query->wire-v8
                      normalize-report
                      shash/report-identity-hash)]

  (deftest-db report-storage
    (testing "should store reports"
      (store-example-report! report timestamp)

      (is (= [{:certname certname}]
             (query-to-vec ["SELECT certname FROM reports"])))

      (is (= [{:hash report-hash}]
             (query-to-vec [(format "SELECT %s AS hash FROM reports" (sutils/sql-hash-as-str "hash"))])))

      (testing "foss doesn't store in the resources column"
        (is (nil? (:resources (first (query-to-vec ["SELECT resources FROM reports"])))))))

    (testing "should store report with long puppet version string"
      (store-example-report!
       (assoc report
              :puppet_version "3.2.1 (Puppet Enterprise 3.0.0-preview0-168-g32c839e)") timestamp)))

  (deftest-db report-with-event-timestamp
    (let [z-report (update-event-timestamps report "2011-01-01T12:00:01Z")
          offset-report (update-event-timestamps report "2011-01-01T12:00:01-0000")]
      (is (= (shash/report-identity-hash (normalize-report z-report))
             (shash/report-identity-hash (normalize-report offset-report))))))

  (deftest-db report-storage-with-environment
    (is (nil? (environment-id "DEV")))

    (store-example-report! (assoc report :environment "DEV") timestamp)

    (is (number? (environment-id "DEV")))

    (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
           [{:certname (:certname report)
             :environment_id (environment-id "DEV")}])))

 (deftest-db report-storage-with-producer
   (let [prod-id (ensure-producer "bar.com")]
     (store-example-report! (assoc report :producer "bar.com") timestamp)

     (is (= (query-to-vec ["SELECT certname, producer_id FROM reports"])
            [{:certname (:certname report)
              :producer_id prod-id}]))))

  (deftest-db report-storage-with-status
    (is (nil? (status-id "unchanged")))

    (store-example-report! (assoc report :status "unchanged") timestamp)

    (is (number? (status-id "unchanged")))

    (is (= (query-to-vec ["SELECT certname, status_id FROM reports"])
           [{:certname (:certname report)
             :status_id (status-id "unchanged")}])))

  (deftest-db report-storage-without-resources
    (testing "should store reports"
      (let [env-id (ensure-environment "DEV")]

        (store-example-report! (assoc-in report [:resource_events :data] []) timestamp)

        (is (= (query-to-vec ["SELECT certname FROM reports"])
               [{:certname (:certname report)}]))
        (is (= (query-to-vec ["SELECT COUNT(1) as num_resource_events FROM resource_events"])
               [{:num_resource_events 0}])))))

  (deftest-db report-storage-with-existing-environment
    (testing "should store reports"
      (let [env-id (ensure-environment "DEV")]

        (store-example-report! (assoc report :environment "DEV") timestamp)

        (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
               [{:certname (:certname report)
                 :environment_id env-id}])))))

  (deftest-db latest-report
    (testing "should flag report as 'latest'"
      (let [node        (:certname report)
            report-hash (:hash (store-example-report! report timestamp))]
        (is (is-latest-report? node report-hash))
        (let [new-report-hash (:hash (store-example-report!
                                      (-> report
                                          (assoc :configuration_version "bar")
                                          (assoc :end_time (now)))
                                      timestamp))]
          (is (is-latest-report? node new-report-hash))
          (is (not (is-latest-report? node report-hash)))))))


  (deftest-db report-cleanup
    (testing "should delete reports older than the specified age"
      (let [report1 (assoc report
                           :certname "foo"
                           :end_time (to-string (-> 5 days ago))
                           :producer_timestamp (to-string (-> 5 days ago)))
            report2 (assoc report
                           :certname "bar"
                           :end_time (to-string (-> 2 days ago))
                           :producer_timestamp (to-string (-> 2 days ago)))]

        (store-example-report! report1 timestamp)
        (store-example-report! report2 timestamp)
        (delete-reports-older-than! (-> 3 days ago))

        (is (= (query-to-vec ["SELECT certname FROM reports"])
               [{:certname "bar"}])))))

  (deftest-db resource-events-cleanup
    (testing "should delete all events for reports older than the specified age"
      (let [report1 (assoc report :end_time (to-string (-> 5 days ago)))
            report1-hash (:hash (store-example-report! report1 timestamp))
            report2 (assoc report :end_time (to-string (-> 2 days ago)))]

        (store-example-report! report2 timestamp)
        (delete-reports-older-than! (-> 3 days ago))
        (is (= #{}
               (set (query-resource-events :latest ["=" "report" report1-hash] {}))))))))

(defn with-db-version [db version f]
  (with-redefs [sutils/db-metadata (delay {:database db
                                           :version version})]
    f))

(deftest-db test-db-unsupported-msg
  (testing "should return a string if db is unsupported"
    (are [db version result]
      (with-db-version db version
        (fn []
          (is (= result (db-unsupported-msg)))))
      "PostgreSQL" [8 1] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 2] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 3] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [8 4] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [9 0] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [9 1] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [9 2] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [9 3] "PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB."
      "PostgreSQL" [9 4] nil)))

(def not-supported-regex #"PostgreSQL DB versions older than 9.4 are no longer supported. Please upgrade Postgres and restart PuppetDB.")

(deftest-db test-unsupported-fail
  (testing "unsupported postgres version"
    (let [fail? (atom false)]
      (with-db-version "PostgreSQL" [9 3]
        (fn []
          (pllog/with-log-output log
            (is (re-find not-supported-regex
                         (tu/with-err-str
                           (validate-database-version #(reset! fail? true)))))
            (is (true? @fail?))
            (is (re-find not-supported-regex (last (first @log)))))))))
  (testing "supported postgres version"
    (let [fail? (atom false)]
      (with-db-version "PostgreSQL" [9 4]
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

(defn fake-hash
  []
  (shash/generic-identity-hash (random/random-string)))

(deftest-db giant-resources-exist
  (testing "resources-exist?"
    (is (= #{} (resources-exist? (set (take 40000 (repeatedly fake-hash))))))))

(deftest-db test-merge-resource-hash
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

(deftest-db test-resources-exist?
  (testing "With empty input"
    (is (= #{} (resources-exist? #{})))))
