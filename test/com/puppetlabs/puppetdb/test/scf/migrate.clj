(ns com.puppetlabs.puppetdb.test.scf.migrate
  (:require [com.puppetlabs.puppetdb.scf.migrate :as migrate]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.scf.migrate]
        [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now]]
        [clojure.test]
        [com.puppetlabs.utils :only [mapvals]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db]]))

(def db (test-db))

(deftest migration
  (testing "applying the migrations"
    (sql/with-connection db
      (clear-db-for-testing!)
      (is (= (schema-version) 0))
      (let [latest-version (apply max (keys migrations))]
        (testing "should migrate the database"
          (migrate!)
          (is (= (schema-version) latest-version)))

        (testing "should not do anything the second time"
          (migrate!)
          (is (= (schema-version) latest-version))))))

  (testing "applied migrations"
    (sql/with-connection db
      (clear-db-for-testing!)
      (is (= (applied-migrations) []))
      (testing "should migrate the database"
        (migrate!)
        (is (= (applied-migrations) (keys migrations))))

      (testing "should not do anything the second time"
        (migrate!)
        (is (= (applied-migrations) (keys migrations))))))

  (testing "pending migrations"
    (testing "should return every migration if the db isn't migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (is (= (pending-migrations) migrations)))))

    (testing "should return nothing if the db is completely migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (migrate!)
        (is (empty? (pending-migrations)))))

  (testing "should throw error if db is at a higher schema rev than we support"
    (with-transacted-connection db
      (migrate!)
      (sql/insert-record :schema_migrations
                         {:version (inc migrate/desired-schema-version) :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate!))))))
