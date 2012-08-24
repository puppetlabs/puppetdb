(ns com.puppetlabs.puppetdb.test.scf.migrate
  (:require [com.puppetlabs.puppetdb.scf.migrate :as migrate]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.scf.migrate]
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

  (testing "pending migrations"
    (testing "should return every migration if the db isn't migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (is (= (pending-migrations) migrations)))))

    (testing "should return nothing if the db is completely migrated"
      (sql/with-connection db
        (clear-db-for-testing!)
        (migrate!)
        (is (empty? (pending-migrations))))))
