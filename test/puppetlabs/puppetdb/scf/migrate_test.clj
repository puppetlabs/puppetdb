(ns puppetlabs.puppetdb.scf.migrate-test
  (:require [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.migrate :as migrate]
            [puppetlabs.puppetdb.scf.migration-legacy :as legacy]
            [puppetlabs.puppetdb.scf.storage :as store]
            [puppetlabs.puppetdb.fixtures :refer [with-db-metadata *db*]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [db-serialize postgres?]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now ago days]]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.testutils :refer [clear-db-for-testing! test-db]])
  (:import [java.sql SQLIntegrityConstraintViolationException]
           [org.postgresql.util PSQLException]))

(use-fixtures :each with-db-metadata)

(defn apply-migration-for-testing!
  [i]
  (let [migration (migrations i)]
    (migration)
    (record-migration! i)))

(defn fast-forward-to-migration!
  [migration-number]
  (doseq [[i migration] (sort migrations)
          :while (<= i migration-number)]
    (migration)
    (record-migration! i)))

(deftest migration
  (testing "pending migrations"
    (testing "should return every migration if the *db* isn't migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (is (= (pending-migrations) migrations))))

    (testing "should return nothing if the *db* is completely migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (migrate! *db*)
        (is (empty? (pending-migrations)))))

    (testing "should return missing migrations if the *db* is partially migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (let [applied '(1 2 4)]
          (doseq [m applied]
            (apply-migration-for-testing! m))
          (is (= (set (keys (pending-migrations)))
                 (difference (set (keys migrations))
                             (set applied))))))))

  (testing "applying the migrations"
    (let [expected-migrations (apply sorted-set (keys migrations))]
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (is (= (applied-migrations) #{}))
        (testing "should migrate the database"
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should not do anything the second time"
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should attempt a partial migration if there are migrations missing"
          (clear-db-for-testing!)
          ;; We are using migration 19 here because it is isolated enough to be able
          ;; to execute on its own. This might need to be changed in the future.
          (doseq [m (filter (fn [[i migration]] (not= i 27)) (pending-migrations))]
            (apply-migration-for-testing! (first m)))
          (is (= (keys (pending-migrations)) '(27)))
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if *db* is at a higher schema rev than we support"
    (jdbc/with-transacted-connection *db*
      (migrate! *db*)
      (jdbc/insert! :schema_migrations
                    {:version (inc migrate/desired-schema-version)
                     :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate! *db*))))))

(deftest migration-14
  (testing "building parameter cache"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      ;; Migrate to prior to the cache table
      (fast-forward-to-migration! 13)

      ;; Now add some resource parameters
      (jdbc/insert!
       :resource_params
       {:resource "1" :name "ensure" :value (db-serialize "file")}
       {:resource "1" :name "owner" :value (db-serialize "root")}
       {:resource "1" :name "group" :value (db-serialize "root")}
       {:resource "2" :name "random" :value (db-serialize "true")}
       ;; resource 3 deliberately left blank
       {:resource "4" :name "ensure" :value (db-serialize "present")}
       {:resource "4" :name "content"
        :value (db-serialize "#!/usr/bin/make\nall:\n\techo done\n")}
       {:resource "5" :name "random" :value (db-serialize "false")}
       {:resource "6" :name "multi" :value (db-serialize ["one" "two" "three"])}
       {:resource "7" :name "hash" :value (db-serialize
                                           (sorted-map "foo" 5 "bar" 10))})

      ;; Now add the parameter cache
      (apply-migration-for-testing! 14)

      ;; Now the cache table should have the json-ified version of
      ;; each resource as the value
      (is (= (map #(update-in % [:parameters] json/parse-string)
                  (query-to-vec "SELECT * FROM resource_params_cache ORDER BY resource"))
             [{:resource "1" :parameters {"ensure" "file"
                                          "owner"  "root"
                                          "group"  "root"}}
              {:resource "2" :parameters {"random" "true"}}
              ;; There should be no resource 3
              {:resource "4" :parameters {"ensure"  "present"
                                          "content" "#!/usr/bin/make\nall:\n\techo done\n"}}
              {:resource "5" :parameters {"random" "false"}}
              {:resource "6" :parameters {"multi" ["one" "two" "three"]}}
              {:resource "7" :parameters {"hash" (sorted-map "foo" 5 "bar" 10)}}])))))

(deftest migration-25
  (testing "should contain same facts before and after migration"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 24)
      (let [current-time (to-timestamp (now))
            yesterday (to-timestamp (-> 1 days ago))]
        (jdbc/insert! :certnames
                      {:name "testing1" :deactivated nil}
                      {:name "testing2" :deactivated nil}
                      {:name "testing3" :deactivated current-time}
                      {:name "testing4" :deactivated nil}
                      {:name "testing5" :deactivated current-time})
        (jdbc/insert! :environments
                      {:id 1 :name "test_env_1"}
                      {:id 2 :name "test_env_2"}
                      {:id 3 :name "test_env_3"}
                      {:id 4 :name "test_env_4"}
                      {:id 5 :name "test_env_5"})
        (jdbc/insert! :certname_facts_metadata
                      {:certname "testing1" :timestamp current-time
                       :environment_id 1}
                      {:certname "testing2" :timestamp current-time
                       :environment_id 2}
                      ;; deactivated node with facts
                      {:certname "testing3" :timestamp current-time
                       :environment_id 3}
                      ;; active node with no facts
                      {:certname "testing4" :timestamp yesterday
                       :environment_id 4}
                      ;; deactivated node with no facts
                      {:certname "testing5" :timestamp yesterday
                       :environment_id 5})
        (jdbc/insert! :certname_facts
                      {:certname "testing1" :name "foo" :value  "1"}
                      {:certname "testing2" :name "bar" :value "true"}
                      {:certname "testing3" :name "baz" :value "false"})

        (apply-migration-for-testing! 25)

        (let [response
              (query-to-vec
                "SELECT path, e.id AS environment_id, e.name AS environment,
                 timestamp, value_string
                 FROM
                 environments e INNER JOIN factsets fs on e.id=fs.environment_id
                 INNER JOIN facts f on f.factset_id=fs.id
                 INNER JOIN fact_values fv on f.fact_value_id=fv.id
                 INNER JOIN fact_paths fp on fp.id=fv.path_id")]
          ;; every node should with facts should be represented
          (is (= response
                 [{:path "foo" :environment_id 1 :environment "test_env_1"
                   :timestamp (to-timestamp current-time) :value_string "1"}
                  {:path "bar" :environment_id 2 :environment "test_env_2"
                   :timestamp (to-timestamp current-time) :value_string "true"}
                  {:path "baz" :environment_id 3 :environment "test_env_3"
                   :timestamp (to-timestamp current-time) :value_string "false"}])))))))

(deftest migration-28
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 27)
    (letfn [(one-row [*db*]
              (first (query-to-vec (format "SELECT * FROM %s LIMIT 1" *db*))))
            (facts-now [c v]
              {:certname c :values v
               :environment nil :timestamp (now) :producer_timestamp nil})
            (random-facts []
              (into {}
                    (for [i (range (+ 1000 (rand-int 100)))]
                      [(str "path-" i "-" (rand-int 3))
                       (str "value-" (rand-int 100))])))]
      (legacy/add-certname-27! "c-x")
      (legacy/add-certname-27! "c-y")
      (legacy/add-certname-27! "c-z")
      (legacy/add-facts-27! (facts-now "c-x" (random-facts)))
      (legacy/add-facts-27! (facts-now "c-y" (random-facts)))
      (legacy/add-facts-27! (facts-now "c-z" (random-facts)))
      ;; Check shapes.
      (is (= #{:factset_id :fact_value_id}
             (set (keys (one-row "facts")))))
      (is (= #{:id :depth :name :path :value_type_id}
             (set (keys (one-row "fact_paths")))))
      (is (= #{:id :value_hash :value_type_id :value_boolean :value_string
               :value_float :value_json :value_integer
               :path_id}
             (set (keys (one-row "fact_values")))))
      ;; Assumes random-facts won't produce a 'bar' value.
      (legacy/add-certname-27! "probe-values")
      (legacy/add-facts-27! (facts-now "probe-values" {"foo-1" "bar"
                                                       "foo-2" "bar"}))

      ;; Add two facts with the same :path, but different values.
      (let [[p1 p2]
            (map :id (jdbc/insert! :fact_paths
                                   {:value_type_id 0
                                    :path "orphan" :name "orphan" :depth 0}
                                   {:value_type_id 1
                                    :path "orphan" :name "orphan" :depth 0}))]
        (jdbc/insert! :fact_values
                      {:path_id p1
                       :value_type_id 0
                       :value_hash (hash/generic-identity-hash "1")
                       :value_string "1"}
                      {:path_id p2
                       :value_type_id 1
                       :value_hash (hash/generic-identity-hash 1)
                       :value_integer 1}))

      (testing "different paths produce different values"
        (is (= 2
               (count (query-to-vec
                       "SELECT * FROM fact_values WHERE value_string = 'bar'")))))
      (apply-migration-for-testing! 28)
      ;; Check shapes.
      (is (= #{:factset_id :fact_path_id :fact_value_id}
             (set (keys (one-row "facts")))))
      (is (= #{:id :depth :name :path}
             (set (keys (one-row "fact_paths")))))
      (is (= #{:id :value_hash :value_type_id :value_boolean :value_string
               :value_float :value_json :value_integer}
             (set (keys (one-row "fact_values")))))
      (testing "same value via different paths reduces to one row"
        (is (= 1
               (count
                (query-to-vec
                 "SELECT * FROM fact_values WHERE value_string = 'bar'")))))
      (testing "fact_paths enforces path uniqueness"
        (if (postgres?)
          (is (thrown? PSQLException
                       (jdbc/insert! :fact_paths
                                     {:path "foo-1" :name "foo-1" :depth 0})))
          (is (thrown? SQLIntegrityConstraintViolationException
                       (jdbc/insert! :fact_paths
                                     {:path "foo-1" :name "foo-1" :depth 0})))))
      (testing "fact_values enforces value_hash uniqueness"
        (if (postgres?)
          (is (thrown?
               PSQLException
               (jdbc/insert! :fact_values
                             {:value_type_id 0
                              :value_hash (hash/generic-identity-hash "bar")
                              :value_string "bar"})))
          (is (thrown?
               SQLIntegrityConstraintViolationException
               (jdbc/insert! :fact_values
                             {:value_type_id 0
                              :value_hash (hash/generic-identity-hash "bar")
                              :value_string "bar"}))))))))

(deftest migration-29
  (testing "should contain same reports before and after migration"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 28)

      (let [current-time (to-timestamp (now))]
        (jdbc/insert! :report_statuses
                      {:status "testing1" :id 1})
        (jdbc/insert! :environments
                      {:id 1 :name "testing1"})
        (jdbc/insert! :certnames
                      {:name "testing1" :deactivated nil}
                      {:name "testing2" :deactivated nil})
        (jdbc/insert! :reports
                      {:hash "01"
                       :configuration_version  "thisisacoolconfigversion"
                       :transaction_uuid "bbbbbbbb-2222-bbbb-bbbb-222222222222"
                       :certname "testing1"
                       :puppet_version "0.0.0"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :environment_id 1
                       :status_id 1}
                      {:hash "0000"
                       :transaction_uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"
                       :configuration_version "blahblahblah"
                       :certname "testing2"
                       :puppet_version "911"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :environment_id 1
                       :status_id 1})

        (jdbc/insert! :latest_reports
                      {:report "01" :certname "testing1"}
                      {:report "0000" :certname "testing2"})

        (apply-migration-for-testing! 29)

        (let [response
              (query-to-vec
                (format "SELECT %s AS hash, r.certname, e.name AS environment, rs.status, %s AS uuid
                         FROM
                         certnames c INNER JOIN reports r on c.latest_report_id=r.id AND c.certname=r.certname
                         INNER JOIN environments e on r.environment_id=e.id
                         INNER JOIN report_statuses rs on r.status_id=rs.id
                         order by c.certname" (sutils/sql-hash-as-str "r.hash") (sutils/sql-uuid-as-str "r.transaction_uuid")))]
          ;; every node should with facts should be represented
          (is (= response
                 [{:hash "01" :environment "testing1" :certname "testing1" :status "testing1" :uuid "bbbbbbbb-2222-bbbb-bbbb-222222222222"}
                  {:hash "0000" :environment "testing1" :certname "testing2" :status "testing1" :uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"}])))

        (let [[id1 id2] (map :id
                              (query-to-vec "SELECT id from reports order by certname"))]

          (let [latest-ids (map :latest_report_id
                                (query-to-vec "select latest_report_id from certnames order by certname"))]
            (is (= [id1 id2] latest-ids))))))))

(deftest migration-37
  (testing "should contain same reports before and after migration"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 36)

      (let [current-time (to-timestamp (now))]
        (jdbc/insert! :report_statuses
                      {:status "testing1" :id 1})
        (jdbc/insert! :environments
                      {:id 1 :environment "testing1"})
        (jdbc/insert! :certnames
                      {:certname "testing1" :deactivated nil}
                      {:certname "testing2" :deactivated nil})
        (jdbc/insert! :reports
                      {:hash (sutils/munge-hash-for-storage "01")
                       :transaction_uuid (sutils/munge-uuid-for-storage
                                          "bbbbbbbb-2222-bbbb-bbbb-222222222222")
                       :configuration_version "thisisacoolconfigversion"
                       :certname "testing1"
                       :puppet_version "0.0.0"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :producer_timestamp current-time
                       :environment_id 1
                       :status_id 1
                       :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                       :logs (sutils/munge-json-for-storage [{:bar "baz"}])}
                      {:hash (sutils/munge-hash-for-storage "0000")
                       :transaction_uuid (sutils/munge-uuid-for-storage
                                          "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa")
                       :configuration_version "blahblahblah"
                       :certname "testing2"
                       :puppet_version "911"
                       :report_format 1
                       :start_time current-time
                       :end_time current-time
                       :receive_time current-time
                       :producer_timestamp current-time
                       :environment_id 1
                       :status_id 1
                       :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                       :logs (sutils/munge-json-for-storage [{:bar "baz"}])})

        (jdbc/update! :certnames
                      {:latest_report_id (if (postgres?) 1 0)}
                      ["certname = ?" "testing1"])
        (jdbc/update! :certnames
                      {:latest_report_id (if (postgres?) 2 1)}
                      ["certname = ?" "testing2"])

        (apply-migration-for-testing! 37)

        (let [response
              (-> (str "SELECT %s AS hash, r.certname, e.environment, rs.status, %s AS uuid, metrics, logs FROM certnames c"
                       " INNER JOIN reports r on c.latest_report_id=r.id AND c.certname=r.certname"
                       " INNER JOIN environments e on r.environment_id=e.id"
                       " INNER JOIN report_statuses rs on r.status_id=rs.id"
                       " ORDER BY c.certname")
                  (format (sutils/sql-hash-as-str "r.hash") (sutils/sql-uuid-as-str "r.transaction_uuid"))
                  query-to-vec)]
          ;; every node should with facts should be represented
          (is (= [{:metrics [{:foo "bar"}] :logs [{:bar "baz"}]
                   :hash "01" :environment "testing1" :certname "testing1" :status "testing1" :uuid "bbbbbbbb-2222-bbbb-bbbb-222222222222"}
                  {:metrics [{:foo "bar"}] :logs [{:bar "baz"}]
                   :hash "0000" :environment "testing1" :certname "testing2" :status "testing1" :uuid "aaaaaaaa-1111-aaaa-1111-aaaaaaaaaaaa"}]
                 (map (comp #(update % :metrics sutils/parse-db-json)
                            #(update % :logs sutils/parse-db-json)) response))))

        (let [[id1 id2] (map :id
                              (query-to-vec "SELECT id from reports order by certname"))]

          (let [latest-ids (map :latest_report_id
                                (query-to-vec "select latest_report_id from certnames order by certname"))]
            (is (= [id1 id2] latest-ids))))))))

(deftest migration-29-producer-timestamp-not-null
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 28)

    (let [current-time (to-timestamp (now))]
      (jdbc/insert! :environments
                    {:id 1 :name "test env"})
      (jdbc/insert! :certnames
                   {:name "foo.local"})
      (jdbc/insert! :catalogs
                    {:hash "18440af604d18536b1c77fd688dff8f0f9689d90"
                     :api_version 1
                     :catalog_version 1
                     :transaction_uuid "95d132b3-cb21-4e0a-976d-9a65567696ba"
                     :timestamp current-time
                     :certname "foo.local"
                     :environment_id 1
                     :producer_timestamp nil})
      (jdbc/insert! :factsets
                    {:timestamp current-time
                     :certname "foo.local"
                     :environment_id 1
                     :producer_timestamp nil})

      (apply-migration-for-testing! 29)

      (let [catalogs-response (query-to-vec "SELECT producer_timestamp FROM catalogs")
            factsets-response (query-to-vec "SELECT producer_timestamp FROM factsets")]
        (is (= catalogs-response [{:producer_timestamp current-time}]))
        (is (= factsets-response [{:producer_timestamp current-time}]))))))

(deftest migration-in-different-schema
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (jdbc/do-commands
     ;; Cleaned up in clear-db-for-testing!
     "CREATE SCHEMA pdbtestschema"
     (format "SET SCHEMA %s" (if (postgres?) "'pdbtestschema'" "pdbtestschema")))
    ((migrations 1))
    (record-migration! 1)
    (let [tables (sutils/sql-current-connection-table-names)]
      ;; Currently sql-current-connection-table-names only looks in public.
      (is (empty? (sutils/sql-current-connection-table-names)))
      (migrate! *db*))))

(deftest test-coalesce-fact-values
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 30)
    (jdbc/insert! :fact_values
                  {:value_type_id 0
                   :value_hash (sutils/munge-hash-for-storage "aaaa")
                   :value_string "foobar"}
                  {:value_type_id 5
                   :value_hash (sutils/munge-hash-for-storage "bbbb")
                   :value_json (json/generate-string {:foo "bar"})}
                  {:value_type_id 1
                   :value_hash (sutils/munge-hash-for-storage "cccc")
                   :value_integer 1})
    (let [pre-migration-values (query-to-vec "SELECT * FROM fact_values")
          value-keys [:value_string :value_integer]]
      (apply-migration-for-testing! 31)
      (let [post-migration-values (query-to-vec "SELECT * FROM fact_values")]
        (is (= (map :value (sort-by :hash post-migration-values))
               (map json/generate-string ["foobar" {:foo "bar"} 1])))))))
