(ns puppetlabs.puppetdb.scf.migrate-test
  (:require [clojure.set :as set]
            [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.migrate :as migrate]
            [puppetlabs.puppetdb.scf.storage :as store]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [db-serialize]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.testutils.db :as tdb
             :refer [*db* clear-db-for-testing!
                     schema-info-map diff-schema-maps]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.time :refer [ago days now to-timestamp]])
  (:import (java.time ZoneId ZonedDateTime)
           (java.sql Timestamp)))

(use-fixtures :each tdb/call-with-test-db)

(def rollup-migration
  (apply min (remove zero? (keys migrations))))

(defn apply-migration-for-testing!
  [i & args]
  (let [migration (migrations i)
        result (apply migration args)]
    (record-migration! i)
    result))

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
        (initialize-schema)
        (is (empty? (pending-migrations)))))

    (testing "should return missing migrations if the *db* is partially migrated"
      (jdbc/with-db-connection *db*
        (clear-db-for-testing!)
        (let [applied [00 28 29 31]]
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
          (initialize-schema)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should not do anything the second time"
          (initialize-schema)
          (is (= (applied-migrations) expected-migrations)))

        (testing "should attempt a partial migration if there are migrations missing"
          (clear-db-for-testing!)
          ;; We are using migration 19 here because it is isolated enough to be able
          ;; to execute on its own. This might need to be changed in the future.
          (doseq [m (filter (fn [[i migration]] (not= i 36)) (pending-migrations))]
            (apply-migration-for-testing! (first m)))
          (is (= (keys (pending-migrations)) '(36)))
          (initialize-schema)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if *db* is at a higher schema rev than we support"
    (jdbc/with-transacted-connection *db*
      (initialize-schema)
      (jdbc/insert! :schema_migrations
                    {:version (inc migrate/desired-schema-version)
                     :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (initialize-schema))))))

(deftest migrations-before-rollup-are-accepted
  (testing "should return missing migrations if the *db* is partially migrated"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (apply-migration-for-testing! 0)
      (jdbc/insert! :schema_migrations {:version (dec rollup-migration)
                                        :time (to-timestamp (now))})
      (apply-migration-for-testing! rollup-migration)
      (is (= true (require-valid-schema))))))

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
        (jdbc/insert-multi! :certnames
                            [{:name "testing1" :deactivated nil}
                             {:name "testing2" :deactivated nil}])
        (jdbc/insert-multi!
          :reports
          [{:hash "01"
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
            :status_id 1}])

        (jdbc/insert-multi! :latest_reports
                            [{:report "01" :certname "testing1"}
                             {:report "0000" :certname "testing2"}])

        (apply-migration-for-testing! 29)

        (let [response
              (query-to-vec
                "SELECT encode(r.hash::bytea, 'hex') AS hash, r.certname,
                         e.name AS environment, rs.status, r.transaction_uuid::text AS uuid
                 FROM certnames c
                 INNER JOIN reports r on c.latest_report_id=r.id
                 AND c.certname=r.certname
                 INNER JOIN environments e on r.environment_id=e.id
                 INNER JOIN report_statuses rs on r.status_id=rs.id
                 order by c.certname")]
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
        (jdbc/insert-multi! :certnames
                            [{:certname "testing1" :deactivated nil}
                             {:certname "testing2" :deactivated nil}])
        (jdbc/insert-multi!
          :reports
          [{:hash (sutils/munge-hash-for-storage "01")
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
            :logs (sutils/munge-json-for-storage [{:bar "baz"}])}])

        (jdbc/update! :certnames
                      {:latest_report_id 1}
                      ["certname = ?" "testing1"])
        (jdbc/update! :certnames
                      {:latest_report_id 2}
                      ["certname = ?" "testing2"])

        (apply-migration-for-testing! 37)

        (let [response
              (query-to-vec
                "SELECT encode(r.hash, 'hex') AS hash, r.certname, e.environment, rs.status,
                        r.transaction_uuid::text AS uuid,
                        coalesce(metrics_json::jsonb, metrics) as metrics,
                        coalesce(logs_json::jsonb, logs) as logs
                   FROM certnames c
                     INNER JOIN reports r
                       ON c.latest_report_id=r.id AND c.certname=r.certname
                     INNER JOIN environments e ON r.environment_id=e.id
                     INNER JOIN report_statuses rs ON r.status_id=rs.id
                   ORDER BY c.certname")]
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
    (let [db-config {:database *db*}
          test-db-name (tdb/subname->validated-db-name (:subname *db*))]
      (clear-db-for-testing!)
      (jdbc/with-db-connection (tdb/db-admin-config)
        (let [db (tdb/subname->validated-db-name (:subname *db*))
              user (get-in tdb/test-env [:user :name])]
          (assert (tdb/valid-sql-id? db))
          (jdbc/do-commands
            (format "grant create on database %s to %s"
                    db (get-in tdb/test-env [:user :name])))))
      (jdbc/do-commands
        "CREATE SCHEMA pdbtestschema"
        "SET SCHEMA 'pdbtestschema'")
      (jdbc/with-db-connection (tdb/db-admin-config test-db-name)
        (jdbc/do-commands
          "DROP EXTENSION pg_trgm"
          "CREATE EXTENSION pg_trgm WITH SCHEMA pdbtestschema"))

      ;; Currently sql-current-connection-table-names only looks in public.
      (is (empty? (sutils/sql-current-connection-table-names)))
      (initialize-schema))))

(deftest test-hash-field-not-nullable
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 40)

    (let [factset-template {:timestamp (to-timestamp (now))
                            :environment_id (store/ensure-environment "prod")
                            :producer_timestamp (to-timestamp (now))}
          factset-data (map (fn [fs]
                               (merge factset-template fs))
                             [{:certname "foo.com"
                               :hash nil}
                              {:certname "bar.com"
                               :hash nil}
                              {:certname "baz.com"
                               :hash (sutils/munge-hash-for-storage "abc123")}])]

      (jdbc/insert-multi! :certnames (map (fn [{:keys [certname]}]
                                            {:certname certname :deactivated nil})
                                          factset-data))
      (jdbc/insert-multi! :factsets factset-data)

      (is (= 2 (:c (first (query-to-vec "SELECT count(*) as c FROM factsets where hash is null")))))

      (apply-migration-for-testing! 41)

      (is (zero? (:c (first (query-to-vec "SELECT count(*) as c FROM factsets where hash is null"))))))))

(deftest test-only-hash-field-change
  (clear-db-for-testing!)
  (fast-forward-to-migration! 40)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 41)
    (is (= {:index-diff nil,
            :table-diff [{:left-only {:nullable? "YES"}
                          :right-only {:nullable? "NO"}
                          :same {:numeric_scale nil
                                 :column_default nil
                                 :character_octet_length nil
                                 :datetime_precision nil
                                 :character_maximum_length nil
                                 :numeric_precision nil
                                 :numeric_precision_radix nil
                                 :data_type "bytea"
                                 :column_name "hash"
                                 :table_name "factsets"}}]
            :constraint-diff [{:left-only nil
                               :right-only
                               {:constraint_name "hash IS NOT NULL"
                                :table_name "factsets"
                                :constraint_type "CHECK"
                                :initially_deferred "NO"
                                :deferrable? "NO"}
                               :same nil}]}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest test-add-producer-to-reports-catalogs-and-factsets-migration
  (clear-db-for-testing!)
  (fast-forward-to-migration! 46)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 47)
    (let [schema-diff (diff-schema-maps before-migration (schema-info-map *db*))]
      (is (= (set [{:left-only nil,
                    :right-only
                    {:schema "public",
                     :table "producers",
                     :index "producers_pkey",
                     :index_keys ["id"],
                     :type "btree",
                     :unique? true,
                     :functional? false,
                     :is_partial false,
                     :primary? true},
                    :same nil}
                   {:left-only nil,
                    :right-only
                    {:schema "public",
                     :table "producers",
                     :index "producers_name_key",
                     :index_keys ["name"],
                     :type "btree",
                     :unique? true,
                     :functional? false,
                     :is_partial false,
                     :primary? false},
                    :same nil}
                   {:left-only nil,
                    :right-only
                    {:schema "public",
                     :table "catalogs",
                     :index "idx_catalogs_prod",
                     :index_keys ["producer_id"],
                     :type "btree",
                     :unique? false,
                     :functional? false,
                     :is_partial false,
                     :primary? false},
                    :same nil}
                   {:left-only nil,
                    :right-only
                    {:schema "public",
                     :table "reports",
                     :index "idx_reports_prod",
                     :index_keys ["producer_id"],
                     :type "btree",
                     :unique? false,
                     :functional? false,
                     :is_partial false,
                     :primary? false},
                    :same nil}
                   {:left-only nil,
                    :right-only
                    {:schema "public",
                     :table "factsets",
                     :index "idx_factsets_prod",
                     :index_keys ["producer_id"],
                     :type "btree",
                     :unique? false,
                     :functional? false,
                     :is_partial false,
                     :primary? false},
                    :same nil}])
             (->> (:index-diff schema-diff)
                  (map #(kitchensink/mapvals (fn [idx] (dissoc idx :user)) %))
                  set)))
      (is (= (set [{:left-only nil,
                    :right-only
                    {:numeric_scale 0,
                     :column_default nil,
                     :character_octet_length nil,
                     :datetime_precision nil,
                     :nullable? "YES",
                     :character_maximum_length nil,
                     :numeric_precision 64,
                     :numeric_precision_radix 2,
                     :data_type "bigint",
                     :column_name "producer_id",
                     :table_name "reports"},
                    :same nil}
                   {:left-only nil,
                    :right-only
                    {:numeric_scale 0,
                     :column_default "nextval('producers_id_seq'::regclass)",
                     :character_octet_length nil,
                     :datetime_precision nil,
                     :nullable? "NO",
                     :character_maximum_length nil,
                     :numeric_precision 64,
                     :numeric_precision_radix 2,
                     :data_type "bigint",
                     :column_name "id",
                     :table_name "producers"},
                    :same nil}
                    {:left-only nil,
                     :right-only
                     {:numeric_scale 0,
                      :column_default nil,
                      :character_octet_length nil,
                      :datetime_precision nil,
                      :nullable? "YES",
                      :character_maximum_length nil,
                      :numeric_precision 64,
                      :numeric_precision_radix 2,
                      :data_type "bigint",
                      :column_name "producer_id",
                      :table_name "catalogs"},
                     :same nil}
                    {:left-only nil,
                     :right-only
                     {:numeric_scale nil,
                      :column_default nil,
                      :character_octet_length 1073741824,
                      :datetime_precision nil,
                      :nullable? "NO",
                      :character_maximum_length nil,
                      :numeric_precision nil,
                      :numeric_precision_radix nil,
                      :data_type "text",
                      :column_name "name",
                      :table_name "producers"},
                     :same nil}
                    {:left-only nil,
                     :right-only
                     {:numeric_scale 0,
                      :column_default nil,
                      :character_octet_length nil,
                      :datetime_precision nil,
                      :nullable? "YES",
                      :character_maximum_length nil,
                      :numeric_precision 64,
                      :numeric_precision_radix 2,
                      :data_type "bigint",
                      :column_name "producer_id",
                      :table_name "factsets"},
                     :same nil}])
             (set (:table-diff schema-diff)))))))

(deftest test-migrate-from-unsupported-version
  (clear-db-for-testing!)
  (fast-forward-to-migration! 28)
  (jdbc/do-commands "DELETE FROM schema_migrations")
  (record-migration! 27)
  (is (thrown-with-msg? IllegalStateException
                        #"Found an old and unsupported database migration.*"
                        (initialize-schema))))

(deftest sha1-agg-test
  (with-test-db
    (jdbc/with-db-connection *db*
      (testing "dual_sha1 function"
        (is (= [{:encode "da23614e02469a0d7c7bd1bdab5c9c474b1904dc"}]
               (query-to-vec "select encode(dual_sha1('a', 'b'), 'hex')"))))

      (testing "sha1_agg custom aggregator"
        ;; this hash is different from the above because it starts by executing
        ;; dual_sha1(0::bytea, 'a'::bytea)
        (is (= [{:encode "420763a8701d3a2585f2a2e005e1f4f108390ad1"}]
               (query-to-vec
                (str "select encode(sha1_agg(val), 'hex') "
                     "from (values (1, 'a'::bytea), (1, 'b'::bytea)) x(gid, val) "
                     "group by gid"))))))))

(deftest migration-50-remove-historical-catalogs
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 49)

    (jdbc/insert! :environments
                  {:id 1 :environment "testing"})

    (jdbc/insert! :certnames
                  {:certname "foo.local"})

    (jdbc/insert! :catalogs
                  {:hash (sutils/munge-hash-for-storage
                           "18440af604d18536b1c77fd688dff8f0f9689d90")
                   :id 1
                   :api_version 1
                   :catalog_version 1
                   :transaction_uuid (sutils/munge-uuid-for-storage
                                       "95d132b3-cb21-4e0a-976d-9a65567696ba")
                   :timestamp (to-timestamp (now))
                   :certname "foo.local"
                   :environment_id 1
                   :producer_timestamp (to-timestamp (now))})
    (jdbc/insert! :catalogs
                  {:hash (sutils/munge-hash-for-storage
                           "18445af604d18536b1c77fd688dff8f0f9689d90")
                   :id 2
                   :api_version 1
                   :catalog_version 1
                   :transaction_uuid (sutils/munge-uuid-for-storage
                                       "95d136b3-cb21-4e0a-976d-9a65567696ba")
                   :timestamp (to-timestamp (now))
                   :certname "foo.local"
                   :environment_id 1
                   :producer_timestamp (to-timestamp (now))})

    (jdbc/insert! :latest_catalogs
                  {:certname_id 1 :catalog_id 2})

    (let [original-catalogs (jdbc/query-to-vec "select id from catalogs")
          _ (apply-migration-for-testing! 50)
          new-catalogs (jdbc/query-to-vec "select id from catalogs")]
      (is (= #{1 2} (set (map :id original-catalogs))))
      (is (= #{2} (set (map :id new-catalogs)))))))

(deftest test-migrate-from-unsupported-version
  (clear-db-for-testing!)
  (fast-forward-to-migration! 28)
  (jdbc/do-commands "DELETE FROM schema_migrations")
  (record-migration! 27)
  (is (thrown-with-msg? IllegalStateException
                        #"Found an old and unsupported database migration.*"
                        (initialize-schema))))

(deftest test-fact-values-value->jsonb
  (clear-db-for-testing!)
  (fast-forward-to-migration! 49)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 51)
    (is (= {:index-diff nil
            :table-diff [{:left-only {:data_type "text",
                                      :character_octet_length 1073741824},
                          :right-only {:data_type "jsonb",
                                       :character_octet_length nil},
                          :same {:table_name "fact_values",
                                 :column_name "value",
                                 :numeric_precision_radix nil,
                                 :numeric_precision nil,
                                 :character_maximum_length nil,
                                 :nullable? "YES",
                                 :datetime_precision nil,
                                 :column_default nil,
                                 :numeric_scale nil}}]
            :constraint-diff [{:left-only
                               {:constraint_name "fact_values_value_type_id_fk",
                                :table_name "fact_values",
                                :constraint_type "FOREIGN KEY",
                                :initially_deferred "NO",
                                :deferrable? "NO"},
                               :right-only nil,
                               :same nil}]}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest test-resource-params-cache-parameters-to-jsonb
  (clear-db-for-testing!)
  (fast-forward-to-migration! 51)
  (let [before-migration (schema-info-map *db*)]
    (jdbc/insert! :resource_params_cache
                  {:resource (sutils/munge-hash-for-storage "a0a0a0")
                   :parameters (json/generate-string {:a "apple" :b {:1 "bear" :2 "button" :3 "butts"}})})
    (jdbc/insert! :resource_params_cache
                  {:resource (sutils/munge-hash-for-storage "b1b1b1")
                   :parameters (json/generate-string {:c "camel" :d {:1 "dinosaur" :2 "donkey" :3 "daffodil"}})})
    (apply-migration-for-testing! 52)
    (testing "should migrate resource_params_cache data correctly"
      (let [responses
            (query-to-vec
              (format "SELECT %s as resource, parameters FROM resource_params_cache"
                      (sutils/sql-hash-as-str "resource")))
            parsed-responses (for [response responses] (assoc response :parameters (sutils/parse-db-json (response :parameters))))]
        (is (= parsed-responses
               [{:resource "a0a0a0" :parameters {:a "apple" :b {:1 "bear" :2 "button" :3 "butts"}}}
                {:resource "b1b1b1" :parameters {:c "camel" :d {:1 "dinosaur" :2 "donkey" :3 "daffodil"}}}]))))
    (testing "should change only value column type"
      (let [schema-diff (diff-schema-maps before-migration (schema-info-map *db*))]
        (is (= #{}
               (->> (:index-diff schema-diff)
                    (map #(kitchensink/mapvals (fn [idx] (dissoc idx :user)) %))
                    set)))
       (is (= #{{:left-only
                {:data_type "text", :character_octet_length 1073741824},
                :right-only
                {:data_type "jsonb", :character_octet_length nil},
                :same
                {:table_name "resource_params_cache",
                 :column_name "parameters",
                 :numeric_precision_radix nil,
                 :numeric_precision nil,
                 :character_maximum_length nil,
                 :nullable? "YES",
                 :datetime_precision nil,
                 :column_default nil,
                 :numeric_scale nil}}}
              (set (:table-diff schema-diff))))))))

(deftest fact-values-reduplication-schema-diff
  ;; This does not check the value_string trgm index since it is
  ;; created opportunistically later in trgm-indexes!
  (clear-db-for-testing!)
  (fast-forward-to-migration! 55)
  (let [initial (schema-info-map *db*)]
    (apply-migration-for-testing! 56)
    (let [migrated (schema-info-map *db*)
          rename-idx (fn [smap idx-id new-table new-name]
                       (let [idxs (:indexes smap)
                             idx-info (get idxs idx-id)
                             new-info (assoc idx-info
                                             :table new-table
                                             :index new-name)
                             new-id (assoc idx-id 0 new-table)]
                         (assert idx-info)
                         (update smap :indexes
                                 #(-> %
                                      (dissoc idx-id)
                                      (assoc new-id new-info)))))
          move-col (fn [smap idx-id new-table]
                     (let [idxs (:tables smap)
                           idx-info (get idxs idx-id)
                           new-info (assoc idx-info
                                           :table_name new-table)
                           new-id (assoc idx-id 0 new-table)]
                       (assert idx-info)
                       (update smap :tables
                               #(-> %
                                    (dissoc idx-id)
                                    (assoc new-id new-info)))))
          exp-smap (-> initial
                       (rename-idx ["fact_values" ["value_integer"]]
                                   "facts" "facts_value_integer_idx")
                       (rename-idx ["fact_values" ["value_float"]]
                                   "facts" "facts_value_float_idx")
                       (move-col ["fact_values" "value_type_id"] "facts")
                       (move-col ["fact_values" "value"] "facts")
                       (move-col ["fact_values" "value_boolean"] "facts")
                       (move-col ["fact_values" "value_integer"] "facts")
                       (move-col ["fact_values" "value_float"] "facts")
                       (move-col ["fact_values" "value_string"] "facts"))
          diff (-> (diff-schema-maps exp-smap migrated)
                   (update :index-diff set)
                   (update :table-diff set)
                   (dissoc :constraint-diff))
          exp-idx-diffs #{ ;; removed indexes
                          {:left-only
                           {:schema "public"
                            :table "fact_values"
                            :index "fact_values_pkey"
                            :index_keys ["id"]
                            :type "btree"
                            :unique? true
                            :functional? false
                            :is_partial false
                            :primary? true
                            :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only
                           {:schema "public"
                            :table "facts"
                            :index "facts_fact_value_id_idx"
                            :index_keys ["fact_value_id"]
                            :type "btree"
                            :unique? false
                            :functional? false
                            :is_partial false
                            :primary? false
                            :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          {:left-only
                           {:schema "public"
                            :table "fact_values"
                            :index "fact_values_value_hash_key"
                            :index_keys ["value_hash"]
                            :type "btree"
                            :unique? true
                            :functional? false
                            :is_partial false
                            :primary? false
                            :user "pdb_test"}
                           :right-only nil
                           :same nil}
                          ;; new indexes
                          {:left-only nil
                           :right-only
                           {:schema "public"
                            :table "facts"
                            :index "facts_factset_id_idx"
                            :index_keys ["factset_id"]
                            :type "btree"
                            :unique? false
                            :functional? false
                            :is_partial false
                            :primary? false
                            :user "pdb_test"}
                           :same nil}
                          {:left-only
                           {:schema "public"
                            :table "facts"
                            :index "facts_factset_id_fact_path_id_fact_key"
                            :index_keys ["factset_id" "fact_path_id"]
                            :type "btree"
                            :unique? true
                            :functional? false
                            :is_partial false
                            :primary? false
                            :user "pdb_test"}
                           :right-only nil
                           :same nil}}
          exp-table-diffs  #{ ;; removed columns
                             {:left-only
                              {:numeric_scale nil
                               :column_default nil
                               :character_octet_length nil
                               :datetime_precision nil
                               :nullable? "NO"
                               :character_maximum_length nil
                               :numeric_precision nil
                               :numeric_precision_radix nil
                               :data_type "bytea"
                               :column_name "value_hash"
                               :table_name "fact_values"}
                              :right-only nil
                              :same nil}
                             {:left-only
                              {:numeric_scale 0
                               :column_default nil
                               :character_octet_length nil
                               :datetime_precision nil
                               :nullable? "NO"
                               :character_maximum_length nil
                               :numeric_precision 64
                               :numeric_precision_radix 2
                               :data_type "bigint"
                               :column_name "fact_value_id"
                               :table_name "facts"}
                              :right-only nil
                              :same nil}
                             {:left-only
                              {:numeric_scale 0
                               :column_default "nextval('fact_values_id_seq'::regclass)"
                               :character_octet_length nil
                               :datetime_precision nil
                               :nullable? "NO"
                               :character_maximum_length nil
                               :numeric_precision 64
                               :numeric_precision_radix 2
                               :data_type "bigint"
                               :column_name "id"
                               :table_name "fact_values"}
                              :right-only nil
                              :same nil}
                             ;; new columns
                             {:left-only nil
                              :right-only
                              {:numeric_scale nil
                               :column_default nil
                               :character_octet_length nil
                               :datetime_precision nil
                               :nullable? "YES"
                               :character_maximum_length nil
                               :numeric_precision nil
                               :numeric_precision_radix nil
                               :data_type "bytea"
                               :column_name "large_value_hash"
                               :table_name "facts"}
                              :same nil}}
          expected {:index-diff exp-idx-diffs
                    :table-diff exp-table-diffs}]
      ;; Handy when trying to see what's wrong.
      (when-not (= expected diff)
        (let [unex (-> diff
                       (update :index-diff set/difference exp-idx-diffs)
                       (update :table-diff set/difference exp-table-diffs))]
          (binding [*out* *err*]
            (println "Unexpected differences:")
            (clojure.pprint/pprint unex))))
      (is (= expected diff)))))

(deftest migration-60-fix-missing-edges-fk-constraint
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 59)
    (let [schema-before-migration (schema-info-map *db*)]

      (jdbc/insert! :environments {:id 1 :environment "testing"})

      (jdbc/insert! :certnames {:certname "a.com"})

      (jdbc/insert! :edges {:certname "a.com"
                            :source (sutils/str->pgobject "bytea" "source-1")
                            :target (sutils/str->pgobject "bytea" "target-1")
                            :type "foo"})
      (jdbc/insert! :edges {:certname "orphaned-node.com"
                            :source (sutils/str->pgobject "bytea" "source-2")
                            :target (sutils/str->pgobject "bytea" "target-2")
                            :type "foo"})

      (apply-migration-for-testing! 60)
      (is (= 0
             (-> (str "select count(*) from edges"
                      "  where certname not in (select certname from certnames)")
                 jdbc/query-to-vec
                 first
                 :count)))

      (let [schema-after-migration (schema-info-map *db*)
            schema-diff (diff-schema-maps schema-before-migration schema-after-migration)]
        (is (= {:index-diff nil
                :table-diff nil
                :constraint-diff [{:left-only nil
                                   :right-only
                                   {:constraint_name "edges_certname_fkey"
                                    :table_name "edges"
                                    :constraint_type "FOREIGN KEY"
                                    :initially_deferred "NO"
                                    :deferrable? "NO"}
                                   :same nil}]}
               schema-diff))))))

(deftest migration-64-rededuplicate-facts
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 63)

    (jdbc/insert! :environments {:id 0 :environment "testing"})

    (jdbc/insert! :certnames {:certname "a.com"})
    (jdbc/insert! :certnames {:certname "b.com"})

    (jdbc/insert! :factsets {:id 0
                             :certname "a.com"
                             :timestamp (to-timestamp (now))
                             :producer_timestamp (to-timestamp (now))
                             :environment_id 0
                             :hash (sutils/munge-hash-for-storage "abcd1234")})

    (jdbc/insert! :factsets {:id 1
                             :certname "b.com"
                             :timestamp (to-timestamp (now))
                             :producer_timestamp (to-timestamp (now))
                             :environment_id 0
                             :hash (sutils/munge-hash-for-storage "1234abcd")})

    (doseq [[id name] [[0 "string_fact"]
                       [1 "int_fact"]
                       [2 "float_fact"]
                       [3 "bool_fact"]
                       [4 "null_fact"]
                       [5 "json_fact"]
                       [6 "json_null_with_json_type"]
                       [7 "json_null_with_null_type"]]]
      (jdbc/insert! :fact_paths {:id id :depth 0 :name name :path name}))

    (doseq [[fact-path-id value-type-id value_key value]
            [[0 0 :value_string "string_value"]
             [1 1 :value_integer 42]
             [2 2 :value_float 4.2]
             [3 3 :value_boolean false]
             [4 4 :value_null nil]
             [5 5 :value_json {:foo "bar"}]
             ;; some databases have a fact value for both json null and sql null
             [6 5 :value_json ::json-null]
             [7 4 :value_null ::json-null]]
            factset-id [0 1]]
      (let [row {:factset_id factset-id
                 :fact_path_id fact-path-id
                 :value_type_id value-type-id
                 ;; reduplicated facts code stored sql NULL for nil values in
                 ;; this column, not json null
                 :value (case value
                          nil nil
                          ::json-null (sutils/munge-jsonb-for-storage nil)
                          (sutils/munge-jsonb-for-storage value))}]
        (jdbc/insert! :facts (case value_key
                               (:value_null :value_json) row
                               (assoc row value_key value)))))

    (is (= {::migrate/vacuum-analyze #{"facts" "fact_values" "fact_paths"}}
           (apply-migration-for-testing! 64)))

    (is (= 6 (:count (first (jdbc/query-to-vec "select count(*) from fact_values")))))
    (is (= 16 (:count (first (jdbc/query-to-vec "select count(*) from facts")))))))

(deftest migration-64-rededuplicate-facts-with-no-good-null-value
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 63)

    (jdbc/insert! :environments {:id 0 :environment "testing"})
    (jdbc/insert! :certnames {:certname "a.com"})
    (jdbc/insert! :factsets {:id 0
                             :certname "a.com"
                             :timestamp (to-timestamp (now))
                             :producer_timestamp (to-timestamp (now))
                             :environment_id 0
                             :hash (sutils/munge-hash-for-storage "abcd1234")})


    (jdbc/insert! :fact_paths {:id 0
                               :depth 0
                               :name "json_null_with_json_type"
                               :path "json_null_with_json_type"})

    (jdbc/insert! :facts {:factset_id 0
                          :fact_path_id 0
                          :value_type_id 5
                          :value (sutils/munge-jsonb-for-storage nil)})


    (jdbc/insert! :fact_paths {:id 1
                               :depth 0
                               :name "json_null_with_null_type"
                               :path "json_null_with_null"})

    (jdbc/insert! :facts {:factset_id 1
                          :fact_path_id 1
                          :value_type_id 5
                          :value (sutils/munge-jsonb-for-storage nil)})


    (jdbc/insert! :fact_paths {:id 2
                               :depth 0
                               :name "sql_null_with_json_type"
                               :path "sql_null_with_json_type"})

    (jdbc/insert! :facts {:factset_id 2
                          :fact_path_id 2
                          :value_type_id 5
                          :value (sutils/munge-jsonb-for-storage nil)})


    (is (= {::migrate/vacuum-analyze #{"facts" "fact_values" "fact_paths"}}
           (apply-migration-for-testing! 64)))

    (is (= 1 (:count (first (jdbc/query-to-vec "select count(*) from fact_values")))))))

(deftest migration-64-fixes-single-fact-with-sql-null-in-json-type
  (jdbc/with-db-connection *db*
    (clear-db-for-testing!)
    (fast-forward-to-migration! 63)

    (jdbc/insert! :environments {:id 0 :environment "testing"})
    (jdbc/insert! :certnames {:certname "a.com"})
    (jdbc/insert! :factsets {:id 0
                             :certname "a.com"
                             :timestamp (to-timestamp (now))
                             :producer_timestamp (to-timestamp (now))
                             :environment_id 0
                             :hash (sutils/munge-hash-for-storage "abcd1234")})

    (jdbc/insert! :fact_paths {:id 0
                               :depth 0
                               :name "sql_null_with_json_type"
                               :path "sql_null_with_json_type"})

    (jdbc/insert! :facts {:factset_id 0
                          :fact_path_id 0
                          :value_type_id 5
                          :value nil})


    (is (= {::migrate/vacuum-analyze #{"facts" "fact_values" "fact_paths"}}
           (apply-migration-for-testing! 64)))

    (is (= [{:value_type_id 4
              :value nil}]
           (jdbc/query-to-vec "select value_type_id, value from fact_values")))))

(deftest migration-73-accounts-for-possible-name-data-created-in-migration-69
  ;; This test simulates a situtation where a user has already applied the original
  ;; version of migration 69 which added a name column to the resource_events table
  ;; before it was replaced by migration 73. This tests checks that any existing data
  ;; in the resource_events name column won't be dropped during migration 73.
  (doseq [hour (range 0 24)]
    (let [current-time (to-timestamp (now))
          other-date (-> (ZonedDateTime/of 2015 10 9 hour 42 54 94 (ZoneId/of "-07:00"))
                         (.toInstant)
                         (Timestamp/from))]
        (jdbc/with-db-connection *db*
          (clear-db-for-testing!)
          ;; mimic the original behavior of migration 69 adding the name column to resource_events
          (with-redefs [migrations (assoc migrations 69 (fn []
                                                          (jdbc/do-commands
                                                           "AlTER TABLE resource_events ADD COLUMN name text;")))]
            (fast-forward-to-migration! 72))

          (jdbc/insert! :report_statuses
                        {:status "testing1" :id 1})
          (jdbc/insert! :environments {:id 0 :environment "testing"})
          (jdbc/insert! :certnames {:certname "a.com"})

          (jdbc/insert-multi!
           :reports
           [{:hash (sutils/munge-hash-for-storage "01")
             :transaction_uuid (sutils/munge-uuid-for-storage
                                "bbbbbbbb-2222-bbbb-bbbb-222222222222")
             :configuration_version "thisisacoolconfigversion"
             :certname "a.com"
             :puppet_version "0.0.0"
             :report_format 1
             :start_time current-time
             :end_time current-time
             :receive_time current-time
             :producer_timestamp current-time
             :environment_id 0
             :status_id 1
             :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
             :logs (sutils/munge-json-for-storage [{:bar "baz"}])}])

          (let [[id1] (map :id
                           (query-to-vec "SELECT id from reports order by certname"))
                row1 {:new_value "\"directory\""
                      :corrective_change false,
                      :property nil
                      :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
                      :report_id id1
                      :old_value "\"absent\""
                      :containing_class "Foo"
                      :certname_id 1
                      :line 11
                      :resource_type "File"
                      :name "keep-this-name-value"
                      :status "success"
                      :resource_title "tmp-directory"
                      :timestamp other-date
                      :containment_path (sutils/to-jdbc-varchar-array ["foo"])
                      :message "created"}]

            (jdbc/insert-multi! :resource_events [row1])
            (apply-migration-for-testing! 73 2)

            (let [hashes (map :event_hash
                              (query-to-vec "SELECT encode(event_hash, 'hex') AS event_hash from resource_events"))
                  hashes-set (set hashes)
                  expected1 (shash/resource-event-identity-pkey row1)
                  resource-event (first (query-to-vec "SELECT * FROM resource_events"))]

              (is (= 1 (count hashes)))
              (is (contains? hashes-set expected1))
              (is (= "keep-this-name-value" (:name resource-event)))))))))

(deftest migration-73-adds-hashes-to-resource-events
  (let [current-time (to-timestamp (now))
        ;; known date that has issues
        other-date (-> (ZonedDateTime/of 2015 10 9 16 42 54 94 (ZoneId/of "-07:00"))
                       (.toInstant)
                       (Timestamp/from))]
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)

      (fast-forward-to-migration! 72)

      (jdbc/insert! :report_statuses
                    {:status "testing1" :id 1})
      (jdbc/insert! :environments {:id 0 :environment "testing"})
      (jdbc/insert! :certnames {:certname "a.com"})
      (jdbc/insert! :certnames {:certname "b.com"})

      (jdbc/insert-multi!
       :reports
       [{:hash (sutils/munge-hash-for-storage "01")
         :transaction_uuid (sutils/munge-uuid-for-storage
                            "bbbbbbbb-2222-bbbb-bbbb-222222222222")
         :configuration_version "thisisacoolconfigversion"
         :certname "a.com"
         :puppet_version "0.0.0"
         :report_format 1
         :start_time current-time
         :end_time current-time
         :receive_time current-time
         :producer_timestamp current-time
         :environment_id 0
         :status_id 1
         :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
         :logs (sutils/munge-json-for-storage [{:bar "baz"}])}

        {:hash (sutils/munge-hash-for-storage "02")
         :transaction_uuid (sutils/munge-uuid-for-storage
                            "aaaaaaaa-2222-bbbb-bbbb-222222222222")
         :configuration_version "thisisacoolconfigversion"
         :certname "a.com"
         :puppet_version "0.0.0"
         :report_format 1
         :start_time (to-timestamp (now))
         :end_time (to-timestamp (now))
         :receive_time (to-timestamp (now))
         :producer_timestamp (to-timestamp (now))
         :environment_id 0
         :status_id 1
         :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
         :logs (sutils/munge-json-for-storage [{:bar "baz"}])}

        {:hash (sutils/munge-hash-for-storage "03")
         :transaction_uuid (sutils/munge-uuid-for-storage
                            "cccccccc-2222-bbbb-bbbb-222222222222")
         :configuration_version "thisisacoolconfigversion"
         :certname "b.com"
         :puppet_version "0.0.0"
         :report_format 1
         :start_time (to-timestamp (now))
         :end_time (to-timestamp (now))
         :receive_time (to-timestamp (now))
         :producer_timestamp (to-timestamp (now))
         :environment_id 0
         :status_id 1
         :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
         :logs (sutils/munge-json-for-storage [{:bar "baz"}])}])

      (let [[id1 id2 id3] (map :id
                               (query-to-vec "SELECT id from reports order by certname"))
            row1 {:new_value "\"directory\""
                  :corrective_change false,
                  :property nil
                  :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
                  :report_id id1
                  :old_value "\"absent\""
                  :containing_class "Foo"
                  :certname_id 1
                  :line 11
                  :resource_type "File"
                  :status "success"
                  :resource_title "tmp-directory"
                  :timestamp other-date
                  :containment_path (sutils/to-jdbc-varchar-array ["foo"])
                  :message "created"}
            row2 {:new_value "\"directory\"",
                  :corrective_change false,
                  :property "ensure",
                  :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp",
                  :report_id id1,
                  :old_value "\"absent\"",
                  :containing_class "Foo",
                  :certname_id 1,
                  :line 11,
                  :resource_type "File",
                  :status "success",
                  :resource_title "tmp-directory",
                  :timestamp other-date
                  :containment_path (sutils/to-jdbc-varchar-array ["foo"])
                  :message "created"}

            ;; matching events from same certname but diff report
            row3 (assoc row1 :report_id id2)
            row4 (assoc row2 :report_id id2)

            ;; matching events from diff certname
            row5 (assoc row1 :report_id id3)
            row6 (assoc row2 :report_id id3)]
        (jdbc/insert-multi!
         :resource_events
         ;; Due to a bug in PUP-9586 and related PDB-4315 it was possible for
         ;; there to be duplicate rows where property was set to null in resource_events.
         ;; All duplicate rows should be filtered out in the migration's sql
         ;; query before the batched insert takes place.
         [row1 row1 row1 row2 row3 row3 row4 row5 row5 row6])

        ;; Run with a batch size of 2 to ensure de-duplication occurs over
        ;; batches.
        (apply-migration-for-testing! 73 2)

        (let [hashes (map :event_hash
                          (query-to-vec "SELECT encode(event_hash, 'hex') AS event_hash from resource_events"))

              hashes-set (set hashes)

              expected1 (shash/resource-event-identity-pkey row1)
              expected2 (shash/resource-event-identity-pkey row2)
              expected3 (shash/resource-event-identity-pkey row3)
              expected4 (shash/resource-event-identity-pkey row4)
              expected5 (shash/resource-event-identity-pkey row5)
              expected6 (shash/resource-event-identity-pkey row6)

              [containment-path] (map :containment_path
                                      (query-to-vec "SELECT containment_path FROM resource_events"))]

          (is (= 6
                 (count hashes)))

          (is (contains? hashes-set expected1))
          (is (contains? hashes-set expected2))
          (is (contains? hashes-set expected3))
          (is (contains? hashes-set expected4))
          (is (contains? hashes-set expected5))
          (is (contains? hashes-set expected6))

          (is (= ["foo"]
                 containment-path)))))))

(deftest migration-70-schema-diff
  (clear-db-for-testing!)
  (fast-forward-to-migration! 69)

  ;; restore the aggregate and function that would be present
  ;; in any existing system
  (jdbc/do-commands
   "CREATE FUNCTION dual_md5(BYTEA, BYTEA) RETURNS bytea AS $$
      BEGIN
        RETURN digest($1 || $2, 'md5');
      END;
    $$ LANGUAGE plpgsql"
   "CREATE AGGREGATE md5_agg (BYTEA)
    (
      sfunc = dual_md5,
      stype = bytea,
      initcond = '\\x00'
    )")

  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 70)

    (is (= {:index-diff nil
            :table-diff nil
            :constraint-diff nil}
           (diff-schema-maps before-migration (schema-info-map *db*))))

    (let [procs (set (map :proname
                          (query-to-vec "SELECT proname FROM pg_proc")))]
      (is (contains? procs "sha1_agg"))
      (is (contains? procs "dual_sha1"))
      (is (not (contains? procs "md5_agg")))
      (is (not (contains? procs "dual_md5"))))))

(deftest autovacuum-vacuum-scale-factor-test
  (clear-db-for-testing!)
  ;; intentionally apply all of the migrations before looking to ensure our autovacuum scale factors aren't lost
  (fast-forward-to-migration! desired-schema-version)
  (let [values {"factsets" "0.80"
                "catalogs" "0.75"
                "certnames" "0.75"}]
    (doseq [[table factor] values]
      (is (= [{:reloptions [(format "autovacuum_vacuum_scale_factor=%s" factor)]}]
             (jdbc/query-to-vec
               (format "SELECT reloptions FROM pg_class WHERE relname = '%s' AND CAST(reloptions as text) LIKE '{autovacuum_vacuum_scale_factor=%s}'"
                       table factor)))))))

(deftest autovacuum-analyze-scale-factor-test
  (clear-db-for-testing!)
  ;; intentionally apply all of the migrations before looking to ensure our autovacuum scale factors aren't lost
  (fast-forward-to-migration! desired-schema-version)
  (create-indexes)
  (let [values {"catalog_resources" "0.01"}]
    (doseq [[table factor] values]
      (is (= [{:reloptions [(format "autovacuum_analyze_scale_factor=%s" factor)]}]
             (jdbc/query-to-vec
               (format "SELECT reloptions FROM pg_class WHERE relname = '%s' AND CAST(reloptions as text) LIKE '{autovacuum_analyze_scale_factor=%s}'"
                       table factor)))))))

(deftest migration-74-changes-hash-of-reports
  (let [current-time (to-timestamp (now))
        old-hash-fn (fn [{:keys [certname puppet_version report_format configuration_version
                                 start_time end_time producer_timestamp resource_events transaction_uuid] :as report}]
                      (hash/generic-identity-hash
                        {:certname certname
                         :puppet_version puppet_version
                         :report_format report_format
                         :configuration_version configuration_version
                         :start_time start_time
                         :end_time end_time
                         :producer_timestamp producer_timestamp
                         :resource_events (sort (map hash/resource-event-identity-string resource_events))
                         :transaction_uuid transaction_uuid}))

        report {:transaction_uuid (sutils/munge-uuid-for-storage
                                    "bbbbbbbb-2222-bbbb-bbbb-222222222222")
                :configuration_version "thisisacoolconfigversion"
                :certname "a.com"
                :puppet_version "0.0.0"
                :report_format 1
                :start_time current-time
                :end_time current-time
                :receive_time current-time
                :producer_timestamp current-time
                :environment_id 0
                :status_id 1
                :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                :logs (sutils/munge-json-for-storage [{:bar "baz"}])}]

    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 73)

      (jdbc/insert! :report_statuses
                    {:status "testing1" :id 1})
      (jdbc/insert! :environments {:id 0 :environment "testing"})
      (jdbc/insert! :certnames {:certname "a.com"})

      (jdbc/insert-multi!
        :reports
        ;; these two reports will end up with the same hash when events
        ;; are removed from the hash calculation. to start with, we need
        ;; to save them to the database using the *old* hashing function
        [(assoc report :hash (sutils/munge-hash-for-storage
                               (old-hash-fn
                                 (assoc report :resource_events [{:new_value "\"directory\"",
                                                                  :corrective_change false,
                                                                  :property "ensure",
                                                                  :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp",
                                                                  :report_id 1,
                                                                  :old_value "\"absent\"",
                                                                  :containing_class "Foo",
                                                                  :certname_id 1,
                                                                  :line 11,
                                                                  :resource_type "File",
                                                                  :status "success",
                                                                  :resource_title "tmp-directory",
                                                                  :timestamp current-time
                                                                  :containment_path (sutils/to-jdbc-varchar-array ["foo"])
                                                                  :message "created"}]))))
         (assoc report :hash (sutils/munge-hash-for-storage
                               (old-hash-fn report)))])

      (let [[id1] (map :id
                       (query-to-vec "SELECT id from reports order by certname"))

            event {:new_value "\"directory\"",
                   :corrective_change false,
                   :property "ensure",
                   :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp",
                   :report_id id1,
                   :old_value "\"absent\"",
                   :containing_class "Foo",
                   :certname_id 1,
                   :line 11,
                   :resource_type "File",
                   :status "success",
                   :resource_title "tmp-directory",
                   :timestamp current-time
                   :containment_path (sutils/to-jdbc-varchar-array ["foo"])
                   :message "created"}]
        (jdbc/insert-multi!
          :resource_events
          [(assoc event :event_hash (sutils/munge-hash-for-storage
                                      (hash/resource-event-identity-pkey event)))]))

      (apply-migration-for-testing! 74)

      (let [hashes (map :hash
                        (query-to-vec "SELECT encode(hash, 'hex') AS hash from reports"))

            expected (hash/report-identity-hash
                       {:transaction_uuid "bbbbbbbb-2222-bbbb-bbbb-222222222222"
                        :configuration_version "thisisacoolconfigversion"
                        :certname "a.com"
                        :puppet_version "0.0.0"
                        :report_format 1
                        :start_time current-time
                        :end_time current-time
                        :producer_timestamp current-time})]
        (is (= 1
               (count hashes)))
        (is (= expected
               (first hashes)))))))

(deftest migration-75-add-report-type-column-with-default
  (testing "reports should get default value of 'agent' for report_type"
    (jdbc/with-db-connection *db*
      (clear-db-for-testing!)
      (fast-forward-to-migration! 74)
      (let [current-time (to-timestamp (now))]
        (jdbc/insert! :report_statuses {:status "testing1" :id 1})
        (jdbc/insert! :environments {:id 0 :environment "testing"})
        (jdbc/insert! :certnames {:certname "testing1"})
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
                       :environment_id 0
                       :status_id 1
                       :metrics (sutils/munge-json-for-storage [{:foo "bar"}])
                       :logs (sutils/munge-json-for-storage [{:bar "baz"}])})
        (apply-migration-for-testing! 75)
        (is (= "agent" (-> (query-to-vec "select * from reports")
                           first :report_type)))))))
