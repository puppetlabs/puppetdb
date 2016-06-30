(ns puppetlabs.puppetdb.scf.migrate-test
  (:require [puppetlabs.puppetdb.scf.hash :as hash]
            [puppetlabs.puppetdb.scf.migrate :as migrate]
            [puppetlabs.puppetdb.scf.storage :as store]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [db-serialize]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.migrate :refer :all]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [now ago days]]
            [clojure.test :refer :all]
            [clojure.set :refer :all]
            [puppetlabs.puppetdb.jdbc :as jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.testutils.db :as tdb
             :refer [*db* clear-db-for-testing!
                     schema-info-map diff-schema-maps]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]])
  (:import [java.sql SQLIntegrityConstraintViolationException]
           [org.postgresql.util PSQLException]))

(use-fixtures :each tdb/call-with-test-db)

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
        (let [applied '(28 29 31)]
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
          (doseq [m (filter (fn [[i migration]] (not= i 36)) (pending-migrations))]
            (apply-migration-for-testing! (first m)))
          (is (= (keys (pending-migrations)) '(36)))
          (migrate! *db*)
          (is (= (applied-migrations) expected-migrations))))))

  (testing "should throw error if *db* is at a higher schema rev than we support"
    (jdbc/with-transacted-connection *db*
      (migrate! *db*)
      (jdbc/insert! :schema_migrations
                    {:version (inc migrate/desired-schema-version)
                     :time (to-timestamp (now))})
      (is (thrown? IllegalStateException (migrate! *db*))))))

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
      (migrate! *db*)
      (indexes! db-config)
      (indexes! db-config))))

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

      (apply jdbc/insert! :certnames (map (fn [{:keys [certname]}]
                                            {:certname certname :deactivated nil})
                                          factset-data))
      (apply jdbc/insert! :factsets factset-data)

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
                                 :table_name "factsets"}}]}

           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest test-adding-historical-catalogs-support-migration
  (clear-db-for-testing!)
  (fast-forward-to-migration! 41)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 42)
    (let [schema-diff (diff-schema-maps before-migration (schema-info-map *db*))]
      (is (= (set [{:same nil :right-only nil
                    :left-only {:numeric_scale 0 :column_default nil
                                :character_octet_length nil :datetime_precision nil
                                :nullable? "NO" :character_maximum_length nil
                                :numeric_precision 64 :numeric_precision_radix 2
                                :data_type "bigint" :column_name "catalog_id"
                                :table_name "catalog_resources"}}
                   {:same nil :left-only nil
                    :right-only {:numeric_scale 0 :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "NO" :character_maximum_length nil
                                 :numeric_precision 64 :numeric_precision_radix 2
                                 :data_type "bigint" :column_name "certname_id"
                                 :table_name "catalog_resources"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale nil :column_default nil :character_octet_length nil
                                 :datetime_precision nil :nullable? "YES"
                                 :character_maximum_length nil :numeric_precision nil
                                 :numeric_precision_radix nil :data_type "uuid"
                                 :column_name "catalog_uuid" :table_name "catalogs"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale nil :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "YES" :character_maximum_length nil
                                 :numeric_precision nil :numeric_precision_radix nil
                                 :data_type "jsonb" :column_name "edges"
                                 :table_name "catalogs"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale nil :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "YES" :character_maximum_length nil
                                 :numeric_precision nil :numeric_precision_radix nil
                                 :data_type "jsonb" :column_name "resources"
                                 :table_name "catalogs"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale 0 :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "NO" :character_maximum_length nil
                                 :numeric_precision 64 :numeric_precision_radix 2
                                 :data_type "bigint" :column_name "catalog_id"
                                 :table_name "latest_catalogs"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale 0 :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "NO" :character_maximum_length nil
                                 :numeric_precision 64 :numeric_precision_radix 2
                                 :data_type "bigint" :column_name "certname_id"
                                 :table_name "latest_catalogs"}}
                   {:left-only nil :same nil
                    :right-only {:numeric_scale nil :column_default nil
                                 :character_octet_length nil :datetime_precision nil
                                 :nullable? "YES" :character_maximum_length nil
                                 :numeric_precision nil :numeric_precision_radix nil
                                 :data_type "uuid" :column_name "catalog_uuid"
                                 :table_name "reports"}}])
             (set (:table-diff schema-diff))))

      (is (= #{{:left-only {:index "idx_catalog_resources_exported_true"}
                :right-only {:index "catalog_resources_exported_idx"}}
               {:left-only {:index "idx_catalog_resources_resource"}
                :right-only {:index "catalog_resources_resource_idx"}}
               {:left-only {:index "idx_catalog_resources_type"}
                :right-only {:index "catalog_resources_type_idx"}}
               {:left-only {:index "idx_catalog_resources_type_title"}
                :right-only {:index "catalog_resources_type_title_idx"}}
               {:left-only {:unique? true :index "catalogs_certname_key"}
                :right-only {:unique? false :index "catalogs_certname_idx"}}
               {:left-only nil
                :right-only {:schema "public" :table "latest_catalogs"
                             :index "latest_catalogs_catalog_id_key" :index_keys ["catalog_id"]
                             :type "btree" :unique? true
                             :functional? false :is_partial false
                             :primary? false}}
               {:left-only nil
                :right-only {:schema "public" :table "reports"
                             :index "reports_catalog_uuid_idx" :index_keys ["catalog_uuid"]
                             :type "btree" :unique? false
                             :functional? false :is_partial false
                             :primary? false}}
               {:left-only nil
                :right-only {:schema "public" :table "latest_catalogs"
                             :index "latest_catalogs_pkey" :index_keys ["certname_id"]
                             :type "btree" :unique? true
                             :functional? false :is_partial false
                             :primary? true}}
               {:left-only {:unique? true}
                :right-only {:unique? false}}
               {:right-only nil
                :left-only {:schema "public"
                            :table "catalog_resources" :index "catalog_resources_pkey"
                            :index_keys ["catalog_id" "type" "title"]
                            :type "btree" :unique? true
                            :functional? false :is_partial false
                            :primary? true}}
               {:right-only {:schema "public" :table "catalog_resources"
                             :index "catalog_resources_pkey1"
                             :index_keys ["certname_id" "type" "title"]
                             :type "btree" :unique? true
                             :functional? false :is_partial false
                             :primary? true}
                :left-only nil}
               {:left-only {:index "resources_hash_expr_idx"}
                :right-only {:index "catalog_resources_encode_idx"}}}
             (->> (:index-diff schema-diff)
                  (map #(kitchensink/mapvals (fn [idx]
                                               (dissoc idx :user)) %))
                  (map #(dissoc % :same))
                  set))))))

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
                        #"Found an old and unuspported database migration.*"
                        (migrate! *db*))))

(deftest md5-agg-test
  (with-test-db
    (jdbc/with-db-connection *db*
      (testing "dual_md5 function"
        (is (= [{:encode "187ef4436122d1cc2f40dc2b92f0eba0"}]
               (query-to-vec "select encode(dual_md5('a', 'b'), 'hex')"))))

      (testing "md5_agg custom aggregator"
        ;; this hash is different from the above because it starts by executing
        ;; dual_md5(0::bytea, 'a'::bytea)
        (is (= [{:encode "bdef73571a96923bdc6b78b5345377d3"}]
               (query-to-vec
                (str "select encode(md5_agg(val), 'hex') "
                     "from (values (1, 'a'::bytea), (1, 'b'::bytea)) x(gid, val) "
                     "group by gid"))))))))


(deftest test-migrate-from-unsupported-version
  (clear-db-for-testing!)
  (fast-forward-to-migration! 28)
  (jdbc/do-commands "DELETE FROM schema_migrations")
  (record-migration! 27)
  (is (thrown-with-msg? IllegalStateException
                        #"Found an old and unuspported database migration.*"
                        (migrate! *db*))))

(deftest md5-agg-test
  (with-test-db
    (jdbc/with-db-connection *db*
      (testing "dual_md5 function"
        (is (= [{:encode "187ef4436122d1cc2f40dc2b92f0eba0"}]
               (query-to-vec "select encode(dual_md5('a', 'b'), 'hex')"))))

      (testing "md5_agg custom aggregator"
        ;; this hash is different from the above because it starts by executing
        ;; dual_md5(0::bytea, 'a'::bytea)
        (is (= [{:encode "bdef73571a96923bdc6b78b5345377d3"}]
               (query-to-vec
                (str "select encode(md5_agg(val), 'hex') "
                     "from (values (1, 'a'::bytea), (1, 'b'::bytea)) x(gid, val) "
                     "group by gid"))))))))

(deftest test-fact-values-value->jsonb
  (clear-db-for-testing!)
  (fast-forward-to-migration! 49)
  (let [before-migration (schema-info-map *db*)]
    (apply-migration-for-testing! 50)
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
                                 :numeric_scale nil}}]}
           (diff-schema-maps before-migration (schema-info-map *db*))))))

(deftest test-resource-params-cache-parameters-to-jsonb
  (clear-db-for-testing!)
  (fast-forward-to-migration! 50)
  (let [before-migration (schema-info-map *db*)]
    (jdbc/insert! :resource_params_cache
                  {:resource (sutils/munge-hash-for-storage "a0a0a0")
                   :parameters (json/generate-string {:a "apple" :b {:1 "bear" :2 "button" :3 "butts"}})})
    (jdbc/insert! :resource_params_cache
                  {:resource (sutils/munge-hash-for-storage "b1b1b1")
                   :parameters (json/generate-string {:c "camel" :d {:1 "dinosaur" :2 "donkey" :3 "daffodil"}})})
    (apply-migration-for-testing! 51)
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
