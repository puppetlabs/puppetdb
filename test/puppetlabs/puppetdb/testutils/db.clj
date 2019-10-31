(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :refer [transform-data]]
            [puppetlabs.puppetdb.testutils :refer [pprint-str]]
            [puppetlabs.puppetdb.utils :refer [flush-and-exit]]))

(defn valid-sql-id? [id]
  (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" id))

(def test-env
  (let [user (env :pdb-test-db-user "pdb_test")
        admin (env :pdb-test-db-admin "pdb_test_admin")]
    ;; Since we're going to use these in raw SQL later (i.e. not via ?).
    (doseq [[who name] [[:user user] [:admin admin]]]
      (when-not (valid-sql-id? name)
        (binding [*out* *err*]
          (println (format "Invalid test %s name %s" who (pr-str name)))
          (flush))
        (flush-and-exit 1)))
    {:host (env :pdb-test-db-host "127.0.0.1")
     :port (env :pdb-test-db-port 5432)
     :user {:name user
            :password (env :pdb-test-db-user-password "pdb_test")}
     :admin {:name admin
             :password (env :pdb-test-db-admin-password "pdb_test_admin")}}))

(def sample-db-config
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname "//127.0.0.1:5432/foo"
   :user "puppetdb"
   :password "xyzzy"})

(defn db-admin-config
  ([] (db-admin-config "postgres"))
  ([database]
    {:classname "org.postgresql.Driver"
     :subprotocol "postgresql"
     :subname (format "//%s:%s/%s" (:host test-env) (:port test-env) database)
     :user (get-in test-env [:admin :name])
     :password (get-in test-env [:admin :password])}))

(defn db-user-config
  [database]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (format "//%s:%s/%s" (:host test-env) (:port test-env) database)
   :user (get-in test-env [:user :name])
   :password (get-in test-env [:user :password])
   :maximum-pool-size 5})

(defn subname->validated-db-name [subname]
  (let [sep (.lastIndexOf subname "/")]
    (assert (pos? sep))
    (let [name (subs subname (inc sep))]
      (assert (valid-sql-id? name))
      name)))

(defn init-db [db]
  (jdbc/with-db-connection db (migrate! db)))

(defn drop-table!
  "Drops a table from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [table-name]
  (jdbc/do-commands (format "DROP TABLE IF EXISTS %s CASCADE" table-name)))

(defn drop-sequence!
  "Drops a sequence from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [sequence-name]
  (jdbc/do-commands (format "DROP SEQUENCE IF EXISTS %s" sequence-name)))

(defn drop-function!
  "Drops a function from the database.  Expects to be called from within a db binding.
  Exercise extreme caution when calling this function!"
  [function-name]
  (jdbc/do-commands (format "DROP FUNCTION IF EXISTS %s CASCADE" function-name)))

(defn clear-db-for-testing!
  "Completely clears the database specified by config (or the current
  database), dropping all puppetdb tables and other objects that exist
  within it. Expects to be called from within a db binding.  You
  Exercise extreme caution when calling this function!"
  ([config]
   (jdbc/with-db-connection config (clear-db-for-testing!)))
  ([]
   (jdbc/do-commands "DROP SCHEMA IF EXISTS pdbtestschema CASCADE")
   (doseq [table-name (cons "test" (sutils/sql-current-connection-table-names))]
     (drop-table! table-name))
   (doseq [sequence-name (cons "test" (sutils/sql-current-connection-sequence-names))]
     (drop-sequence! sequence-name))
   (doseq [function-name (sutils/sql-current-connection-function-names)]
          (drop-function! function-name))))

(def ^:private pdb-test-id (env :pdb-test-id))

(def ^:private template-name
  (if pdb-test-id
    (let [name (str "pdb_test_" pdb-test-id "_template")]
      (assert (valid-sql-id? name))
      name)
    "pdb_test_template"))

(def ^:private template-created (atom false))

(defn- ensure-pdb-db-templates-exist []
  (locking ensure-pdb-db-templates-exist
    (when-not @template-created
      (assert (valid-sql-id? template-name))
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. #(jdbc/with-db-connection (db-admin-config)
                   (jdbc/do-commands-outside-txn
                    (format "drop database if exists %s" template-name)))))
      (jdbc/with-db-connection (db-admin-config)
        (jdbc/do-commands-outside-txn
         (format "drop database if exists %s" template-name)
         (format "create database %s" template-name)))
      (jdbc/with-db-connection (db-admin-config template-name)
        (jdbc/do-commands-outside-txn
         "create extension if not exists pg_trgm"
         "create extension if not exists pgcrypto"))
      (let [cfg (db-user-config template-name)]
        (jdbc/with-db-connection cfg
          (migrate! cfg)))
      (reset! template-created true))))

(def ^:private test-db-counter (atom 0))

(defn create-temp-db []
  "Creates a temporary test database.  Prefer with-test-db, etc."
  (ensure-pdb-db-templates-exist)
  (let [n (swap! test-db-counter inc)
        db-name (if-not pdb-test-id
                  (str "pdb_test_" n)
                  (str "pdb_test_" pdb-test-id "_" n))]
    (assert (valid-sql-id? db-name))
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)
       (format "create database %s template %s" db-name template-name)))
    (db-user-config db-name)))

(def ^:dynamic *db* nil)

(defn- disconnect-db-user [db user]
  "Forcibly disconnects all connections from the specified user to the
  named db.  Requires that the current DB session has sufficient
  authorization."
  (jdbc/query-to-vec
   [(str "select pg_terminate_backend (pg_stat_activity.pid)"
         "  from pg_stat_activity"
         "  where pg_stat_activity.datname = ?"
         "    and pg_stat_activity.usename = ?")
    db
    user]))

(defn drop-test-db [db-config]
  (let [db-name (subname->validated-db-name (:subname db-config))]
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands
       (format "alter database \"%s\" with connection limit 0" db-name)))
    (let [config (db-user-config "postgres")]
      (jdbc/with-db-connection config
        ;; We'll need this until we can upgrade bonecp (0.8.0
        ;; appears to fix the problem).
        (disconnect-db-user db-name (:user config))))
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)))))

(def preserve-test-db-on-failure
  (boolean (re-matches #"yes|true|1" (env :pdb-test-keep-db-on-fail ""))))

(defn call-with-db-info-on-failure-or-drop
  "Calls (f), and then if there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  [db-config f]
  (let [before (some-> clojure.test/*report-counters* deref)]
    (try
      (f)
      (finally
        (if-not preserve-test-db-on-failure
          (drop-test-db db-config)
          (let [after (some-> clojure.test/*report-counters* deref)]
            (if (and (= (:error before) (:error after))
                     (= (:fail before) (:fail after)))
              (drop-test-db db-config)
              (clojure.test/with-test-out
                (println "Leaving test database intact:" (:subname *db*))))))))))

(defmacro with-db-info-on-failure-or-drop
  "Evaluates body in the context of call-with-db-info-on-failure-or-drop."
  [db-config & body]
  `(call-with-db-info-on-failure-or-drop ~db-config (fn [] ~@body)))

(defn call-with-test-db
  "Binds *db* to a clean, migrated test database, makes it the active
  jdbc connection via with-db-connection, and calls (f).  If there are
  no clojure.tests failures or errors, drops the database, otherwise
  displays its subname."
  [f]
  (binding [*db* (create-temp-db)]
    (with-db-info-on-failure-or-drop *db*
      (jdbc/with-db-connection *db*
        (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
          (f))))))

(defmacro with-test-db [& body]
  `(call-with-test-db (fn [] ~@body)))

(defn call-with-test-dbs [n f]
  "Calls (f db-config ...) with n db-config arguments, each
  representing a database created and protected by with-test-db."
  (if (pos? n)
    (with-test-db
      (call-with-test-dbs (dec n) (partial f *db*)))
    (f)))

(defn without-db-var
  "Binds the java.jdbc dtabase connection to nil. When running a unit
   test using `call-with-test-db`, jint/*db* will be bound. If the routes
   being tested don't explicitly bind the db connection, it will use
   one bound in call-with-test-db. This causes a problem at runtime that
   won't show up in the unit tests. This fixture can be used around
   route testing code to ensure that the route has it's own db
   connection."
  [f]
  (binding [jdbc/*db* nil]
    (f)))

(defn defaulted-write-db-config
  "Defaults and converts `db-config` from the write database INI
  format to the internal write database format"
  [db-config]
  (transform-data conf/write-database-config-in
                  conf/write-database-config-out
                  db-config))

(defn defaulted-read-db-config
  "Defaults and converts `db-config` from the read-database INI format
  to the internal read database format"
  [db-config]
  (transform-data conf/database-config-in
                  conf/database-config-out
                  db-config))

(def antonym-data {"absence"    "presence"
                   "abundant"   "scarce"
                   "accept"     "refuse"
                   "accurate"   "inaccurate"
                   "admit"      "deny"
                   "advance"    "retreat"
                   "advantage"  "disadvantage"
                   "alive"      "dead"
                   "always"     "never"
                   "ancient"    "modern"
                   "answer"     "question"
                   "approval"   "disapproval"
                   "arrival"    "departure"
                   "artificial" "natural"
                   "ascend"     "descend"
                   "blandness"  "zest"
                   "lethargy"   "zest"})

(defn insert-entries [m]
  (jdbc/insert-multi! :test [:key :value] (seq m)))

(defn call-with-antonym-test-database
  [function]
  (with-test-db
    (jdbc/with-db-transaction []
      (jdbc/do-commands
       (sql/create-table-ddl :test
                             [[:key "VARCHAR(256)" "PRIMARY KEY"]
                              [:value "VARCHAR(256)" "NOT NULL"]]))
      (insert-entries antonym-data))
    (function)))

(def indexes-sql
  "SELECT
  U.usename                AS user,
  ns.nspname               AS schema,
  idx.indrelid :: REGCLASS AS table,
  i.relname                AS index,
  idx.indisunique          AS is_unique,
  idx.indisprimary         AS is_primary,
  am.amname                AS type,
  ARRAY(
        SELECT pg_get_indexdef(idx.indexrelid, k + 1, TRUE)
        FROM generate_subscripts(idx.indkey, 1) AS k
        ORDER BY k
  ) AS index_keys,
  (idx.indexprs IS NOT NULL) OR (idx.indkey::int[] @> array[0]) AS is_functional,
  idx.indpred IS NOT NULL AS is_partial
FROM pg_index AS idx
  JOIN pg_class AS i
    ON i.oid = idx.indexrelid
  JOIN pg_am AS am
    ON i.relam = am.oid
  JOIN pg_namespace AS NS ON i.relnamespace = NS.OID
  JOIN pg_user AS U ON i.relowner = U.usesysid
WHERE NOT nspname LIKE 'pg%'
ORDER BY idx.indrelid :: REGCLASS, i.relname;")

(defn db->index-map
  "Converts the metadata columns from their database names/formats to
  something more natural to use in Clojure"
  [row]
  (-> row
      (update :table #(.getValue %))
      (clojure.set/rename-keys {:is_unique :unique?
                                :is_functional :functional?
                                :is_primary :primary?})))

(defn query-indexes
  "Returns the list of all PuppetDB created indexes, sorted by table,
  then the name of the index"
  [db]
  (jdbc/with-db-connection db
    (let [indexes (sort-by (juxt :table :index_keys)
                           (map db->index-map (jdbc/query-to-vec indexes-sql)))]
      (kitchensink/mapvals first
                           (group-by (juxt :table :index_keys) indexes)))))

(def table-column-sql
  "SELECT c.table_name,
          c.column_name,
          c.column_default,
          c.is_nullable,
          c.data_type,
          c.datetime_precision,
          c.numeric_precision,
          c.numeric_precision_radix,
          c.numeric_scale,
          c.character_maximum_length,
          c.character_octet_length
   FROM information_schema.tables t
        inner join information_schema.columns c on t.table_name = c.table_name
   where t.table_schema = 'public';")

(defn db->table-map
  "Converts the metadata column names to something more natural in Clojure"
  [row]
  (clojure.set/rename-keys row {:is_nullable :nullable?}))

(defn query-tables
  "Return a map, keyed by [<table-name> <column-name>] that contains
  each PuppetDB created table+column in the database"
  [db]
  (jdbc/with-db-connection db
    (let [tables (sort-by (juxt :table_name :column_name)
                          (map db->table-map (jdbc/query-to-vec table-column-sql)))]
      (kitchensink/mapvals first
                           (group-by (juxt :table_name :column_name) tables)))))

(def constraints-sql
  "SELECT constraint_name,
          table_name,
          constraint_type,
          is_deferrable,
          initially_deferred
   FROM information_schema.table_constraints
   WHERE table_schema = 'public'
         and constraint_type != 'CHECK'")

;; check constraints get autogenerated names; instead, use their check clause as
;; the name.
(def check-constraints-sql
  "SELECT cc.check_clause as constraint_name,
          c.table_name,
          c.constraint_type,
          c.is_deferrable,
          c.initially_deferred
   FROM information_schema.table_constraints c
   LEFT JOIN information_schema.check_constraints cc
     ON c.constraint_name = cc.constraint_name
   WHERE c.table_schema = 'public'
     AND c.constraint_type = 'CHECK'")

(defn db->constraint-map
  "Converts the constraint column names to something more natural in Clojure"
  [row]
  (clojure.set/rename-keys row {:is_deferrable :deferrable?}))

(defn query-constraints
  [db]
  (jdbc/with-db-connection db
    (let [constraints (sort-by (juxt :table_name :constraint_name)
                               (map db->constraint-map (concat (jdbc/query-to-vec constraints-sql)
                                                               (jdbc/query-to-vec check-constraints-sql))))]
      (kitchensink/mapvals first
                           (group-by (juxt :table_name :constraint_name) constraints)))))

(defn schema-info-map [db-props]
  {:indexes (query-indexes db-props)
   :tables (query-tables db-props)
   :constraints (query-constraints db-props)})

(defn diff' [left right]
  (let [[left-only right-only same] (clojure.data/diff left right)]
    (when (or left-only right-only)
      {:left-only left-only
       :right-only right-only
       :same same})))

(defn diff-schema-data [left right]
  (->> (concat (keys left) (keys right))
       (into (sorted-set))
       (keep (fn [data-map-key]
               (diff' (get left data-map-key)
                      (get right data-map-key))))))

(defn diff-schema-maps [left right]
  (let [index-diff (diff-schema-data (:indexes left) (:indexes right))
        table-diffs (diff-schema-data (:tables left) (:tables right))
        constraint-diffs (diff-schema-data (:constraints left) (:constraints right))]
    {:index-diff (seq index-diff)
     :table-diff (seq table-diffs)
     :constraint-diff (seq constraint-diffs)}))

(defn output-table-diffs [diff-list]
  (str/join "\n\n------------------------------\n\n"
            (map (fn [{:keys [left-only right-only same]}]
                   (str "Left Only:\n" (pprint-str left-only)
                        "\nRight Only:\n" (pprint-str right-only)
                        "\nSame:\n" (pprint-str same)))
                 diff-list)))
