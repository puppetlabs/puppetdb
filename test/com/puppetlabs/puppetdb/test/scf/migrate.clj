(ns com.puppetlabs.puppetdb.test.scf.migrate
  (:require [com.puppetlabs.puppetdb.scf.migrate :as migrate]
            [com.puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.scf.migrate]
        [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now ago days secs]]
        [clojure.test]
        [clojure.set]
        [puppetlabs.kitchensink.core :only [mapvals]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db]]
        [com.puppetlabs.puppetdb.testutils.reports :only [store-example-report! store-v2-example-report!]]))

(def db (test-db))

(defn apply-migration-for-testing
  [i]
  (let [migration (migrations i)]
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
            (apply-migration-for-testing m))
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
          ;; We are using migration 13 here because it is isolated enough to be able
          ;; to execute on its own. This might need to be changed in the future.
          (doseq [m (filter (fn [[i migration]] (not= i 13)) (pending-migrations))]
            (apply-migration-for-testing (first m)))
          (is (= (keys (pending-migrations)) '(13)))
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
      (doseq [[i migration] (sort migrations)
              :while (< i 14)]
        (migration)
        (record-migration! i))

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
      (add-parameter-cache)
      (record-migration! 14)

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
  (testing "should contain same facts before and after migration")
  (sql/with-connection db
    (clear-db-for-testing!)
    (doseq [[i migration] (sort migrations)
            :while (< i 25)]
      (migration)
      (record-migration! i))
    (let [current-time (to-timestamp (now))]
      (sql/insert-records
       :certnames
       {:name "testing1" :deactivated nil}
       {:name "testing2" :deactivated nil})
      (sql/insert-records
        :environments
       {:id 1 :name "test_env_1"}
       {:id 2 :name "test_env_2"})
      (sql/insert-records
       :certname_facts_metadata
       {:certname "testing1" :timestamp current-time :environment_id 1}
       {:certname "testing2" :timestamp current-time :environment_id 2})
      (sql/insert-records
       :certname_facts
       {:certname "testing1" :name "foo"  :value  "1"}
       {:certname "testing2" :name "bar"  :value "true"})

      (structured-facts)
      (record-migration! 25)

      (let [response
            (query-to-vec
              "SELECT path,e.id AS environment_id, e.name AS environment,timestamp,value_string
               FROM
               environments e INNER JOIN factsets fs on e.id=fs.environment_id
                              INNER JOIN facts f on f.factset_id=fs.id
                              INNER JOIN fact_values fv on f.fact_value_id=fv.id
                              INNER JOIN fact_paths fp on fp.id=fv.path_id")]
        (is (= response
               [{:path "foo", :environment_id 1, :environment "test_env_1",
                 :timestamp (to-timestamp current-time), :value_string "1"}
                {:path "bar", :environment_id 2, :environment "test_env_2",
                 :timestamp (to-timestamp current-time),
                :value_string "true"}]))))))
