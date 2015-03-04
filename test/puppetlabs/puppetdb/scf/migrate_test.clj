(ns puppetlabs.puppetdb.scf.migrate-test
  (:require [puppetlabs.puppetdb.scf.migrate :as migrate]
            [puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now ago days secs]]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.jdbc :refer [query-to-vec with-transacted-connection]]
            [puppetlabs.puppetdb.testutils :refer [clear-db-for-testing! test-db]]))

(def db (test-db))

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
    (testing "should return every migration if the db isn't migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (is (= (pending-migrations) migrations))))

    (testing "should return nothing if the db is completely migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (migrate!)
        (is (empty? (pending-migrations)))))

    (testing "should return missing migrations if the db is partially migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (let [applied '(1 2 4)]
          (doseq [m applied]
            (apply-migration-for-testing! m))
          (is (= (set (keys (pending-migrations)))
                 (difference (set (keys migrations))
                             (set applied))))))))

  (testing "applying the migrations"
    (let [expected-migrations (apply sorted-set (keys migrations))]
      (sql/with-connection db
        (clear-db-for-testing!)
        (is (= (applied-migrations) #{}))
        (testing "should migrate the database"
          (migrate!)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should not do anything the second time"
          (migrate!)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should attempt a partial migration if there are migrations missing"
          (clear-db-for-testing!)
          ;; We are using migration 28 here because it is isolated enough to be able
          ;; to execute on its own. This might need to be changed in the future.
          (doseq [m (filter (fn [[i migration]] (not= i 28)) (pending-migrations))]
            (apply-migration-for-testing! (first m)))
          (is (= (keys (pending-migrations)) '(28)))
          (migrate!)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if db is at a higher schema rev than we support"
    (with-transacted-connection db
      (migrate!)
      (sql/insert-record :schema_migrations
                         {:version (inc migrate/desired-schema-version) :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate!))))))

(deftest migration-14
  (testing "building parameter cache"
    (sql/with-connection db
      (clear-db-for-testing!)
      ;; Migrate to prior to the cache table
      (fast-forward-to-migration! 13)

      ;; Now add some resource parameters
      (sql/insert-records
       :resource_params
       {:resource "1" :name "ensure"  :value (db-serialize "file")}
       {:resource "1" :name "owner"   :value (db-serialize "root")}
       {:resource "1" :name "group"   :value (db-serialize "root")}
       {:resource "2" :name "random"  :value (db-serialize "true")}
       ;; resource 3 deliberately left blank
       {:resource "4" :name "ensure"  :value (db-serialize "present")}
       {:resource "4" :name "content" :value (db-serialize "#!/usr/bin/make\nall:\n\techo done\n")}
       {:resource "5" :name "random"  :value (db-serialize "false")}
       {:resource "6" :name "multi"   :value (db-serialize ["one" "two" "three"])}
       {:resource "7" :name "hash"    :value (db-serialize (sorted-map  "foo" 5 "bar" 10))})

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
    (sql/with-connection db
      (clear-db-for-testing!)
      (fast-forward-to-migration! 24)
      (let [current-time (to-timestamp (now))
            yesterday (to-timestamp (-> 1 days ago))]
        (sql/insert-records
          :certnames
          {:name "testing1" :deactivated nil}
          {:name "testing2" :deactivated nil}
          {:name "testing3" :deactivated current-time}
          {:name "testing4" :deactivated nil}
          {:name "testing5" :deactivated current-time})
        (sql/insert-records
          :environments
          {:id 1 :name "test_env_1"}
          {:id 2 :name "test_env_2"}
          {:id 3 :name "test_env_3"}
          {:id 4 :name "test_env_4"}
          {:id 5 :name "test_env_5"})
        (sql/insert-records
          :certname_facts_metadata
          {:certname "testing1" :timestamp current-time :environment_id 1}
          {:certname "testing2" :timestamp current-time :environment_id 2}
          ;; deactivated node with facts
          {:certname "testing3" :timestamp current-time :environment_id 3}
          ;; active node with no facts
          {:certname "testing4" :timestamp yesterday :environment_id 4}
          ;; deactivated node with no facts
          {:certname "testing5" :timestamp yesterday :environment_id 5})
        (sql/insert-records
          :certname_facts
          {:certname "testing1" :name "foo"  :value  "1"}
          {:certname "testing2" :name "bar"  :value "true"}
          {:certname "testing3" :name "baz"  :value "false"})

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

(deftest migration-29
  (testing "should contain same reports before and after migration"
    (sql/with-connection db
      (clear-db-for-testing!)
      (fast-forward-to-migration! 28)

      (let [current-time (to-timestamp (now))]
        (sql/insert-records
          :report_statuses
          {:status "testing1" :id 1})
        (sql/insert-records
          :environments
          {:id 1 :name "testing1"})
        (sql/insert-records
          :certnames
          {:name "testing1" :deactivated nil}
          {:name "testing2" :deactivated nil})

        (sql/insert-records
          :reports
          {:hash                   "thisisacoolhash"
           :configuration_version  "thisisacoolconfigversion"
           :certname               "testing1"
           :puppet_version         "0.0.0"
           :report_format          1
           :start_time             current-time
           :end_time               current-time
           :receive_time           current-time
           :environment_id         1
           :status_id              1}
          {:hash "blahblah"
           :configuration_version "blahblahblah"
           :certname "testing2"
           :puppet_version "911"
           :report_format 1
           :start_time current-time
           :end_time current-time
           :receive_time current-time
           :environment_id 1
           :status_id 1})

        (sql/insert-records
          :latest_reports
          {:report                 "thisisacoolhash"
           :certname               "testing1"}
          {:report                 "blahblah"
           :certname               "testing2"})

        (apply-migration-for-testing! 29)

        (let [response
              (query-to-vec
                "SELECT r.hash, r.certname, e.name AS environment, rs.status
                 FROM
                 certnames c INNER JOIN reports r on c.latest_report_id=r.id AND c.name=r.certname
                 INNER JOIN environments e on r.environment_id=e.id
                 INNER JOIN report_statuses rs on r.status_id=rs.id
                 order by c.name")]
          ;; every node should with facts should be represented
          (is (= response
                 [{:hash "thisisacoolhash" :environment "testing1" :certname "testing1" :status "testing1"}
                  {:hash "blahblah" :environment "testing1" :certname "testing2" :status "testing1"}])))

        (let [[id1 id2] (map :id
                              (query-to-vec "SELECT id from reports order by certname"))]

          (let [latest-ids (map :latest_report_id
                                (query-to-vec "select latest_report_id from certnames order by name"))]
            (is (= [id1 id2] latest-ids))))))))
