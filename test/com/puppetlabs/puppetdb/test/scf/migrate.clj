(ns com.puppetlabs.puppetdb.test.scf.migrate
  (:require [com.puppetlabs.puppetdb.scf.migrate :as migrate]
            [com.puppetlabs.puppetdb.scf.storage :refer (db-serialize)]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.puppetdb.scf.migrate]
        [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [now ago days secs]]
        [clojure.test]
        [clojure.set]
        [com.puppetlabs.utils :only [mapvals]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db]]
        [com.puppetlabs.puppetdb.examples.report]
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report!]]))

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
          ;; we are using migration 6 here because it's just dropping an index,
          ;; so we know for sure that it can be applied in any order.
          (doseq [m (filter (fn [[i migration]] (not= i 6)) (pending-migrations))]
            (apply-migration-for-testing (first m)))
          (is (= (keys (pending-migrations)) '(6)))
          (migrate!)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if db is at a higher schema rev than we support"
    (with-transacted-connection db
      (migrate!)
      (sql/insert-record :schema_migrations
                         {:version (inc migrate/desired-schema-version) :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate!)))))

  (testing "burgundy migration should populate the new `latest_reports` table correctly"
    (sql/with-connection db
      (clear-db-for-testing!)
      (let [latest-report-migration   13
            applied                   (range 1 latest-report-migration)
            basic                     (:basic reports)
            old-timestamp             (ago (days 1))
            older-timestamp           (ago (days 2))
            new-timestamp             (now)
            node1                     "foocertname"
            node2                     "barcertname"
            store-report-fn           (fn [certname end-time received-time]
                                        (store-example-report!
                                          (-> basic
                                            (assoc :certname certname)
                                            (assoc :end-time end-time))
                                          received-time
                                          false))]
        ;; first we run all of the schema migrations *prior* to the
        ;; introduction of the `latest_reports` table
        (doseq [i applied]
          (apply-migration-for-testing i))
        ;; now we create a few reports for a few nodes.
        ;; we'll make the `received-time` older for the newest report,
        ;; to make sure that `latest_reports` table is being populated
        ;; by `end-time` and not by `received-time`
        (store-report-fn node1 new-timestamp (ago (secs 20)))
        (store-report-fn node1 old-timestamp (now))
        (store-report-fn node1 older-timestamp (now))
        (store-report-fn node2 new-timestamp (ago (secs 30)))
        (store-report-fn node2 old-timestamp (now))
        (store-report-fn node2 older-timestamp (now))
        ;; now we finish the migration (which should introduce the `latest_reports`
        ;; table and populate it.
        (migrate!)
        ;; now we can validate the data from the migration.
        (let [latest_reports (query-to-vec "SELECT latest_reports.*, reports.end_time from latest_reports INNER JOIN reports ON latest_reports.report = reports.hash")]
          (is (= 2 (count latest_reports)))
          (doseq [report latest_reports]
            (is (= (:end_time report) (to-timestamp new-timestamp)))))))))

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
