(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :refer [transform-data]]
            [puppetlabs.puppetdb.testutils :refer [pprint-str]]))

(defn valid-sql-id? [id]
  (re-matches #"[a-zA-Z][a-zA-Z0-9_]*" id))

(def test-env
  (let [user (env :pdb-test-db-user (env :puppetdb-dbuser "pdb_test"))
        admin (env :pdb-test-db-admin "pdb_test_admin")]
    ;; Since we're going to use these in raw SQL later (i.e. not via ?).
    (doseq [[who name] [[:user user] [:admin admin]]]
      (when-not (valid-sql-id? name)
        (binding [*out* *err*]
          (println (format "Invalid test %s name %s" who (pr-str name)))
          (flush))
        (System/exit 1)))
    {:host (env :pdb-test-db-host "127.0.0.1")
     :port (env :pdb-test-db-port 5432)
     :user {:name user
            :password (env :pdb-test-db-user-password "pdb_test")}
     :admin {:name admin
             :password (env :pdb-test-db-admin-password "pdb_test_admin")}}))

(def sample-db-config
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (env :puppetdb-dbsubname "//127.0.0.1:5432/foo")
   :user "puppetdb"
   :password "xyzzy"})

(defn db-admin-config
  ([] (db-admin-config "template1"))
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
   :password (get-in test-env [:user :password])})

(defn subname->validated-db-name [subname]
  (let [sep (.lastIndexOf subname "/")]
    (assert (pos? sep))
    (let [name (subs subname (inc sep))]
      (assert (valid-sql-id? name))
      name)))

(defn init-db [db read-only?]
  (jdbc/with-db-connection db (migrate! db))
  (jdbc/pooled-datasource (assoc db :read-only? read-only?)))

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
     (drop-sequence! sequence-name))))

(def ^:private templates-created (atom false))

(defn- ensure-pdb-db-templates-exist []
  (locking ensure-pdb-db-templates-exist
    (when-not @templates-created
      (jdbc/with-db-connection (db-admin-config)
        (jdbc/do-commands-outside-txn
         (format "drop database if exists pdb_test_template")
         (format "create database pdb_test_template")))
      (let [cfg (db-user-config "pdb_test_template")]
        (jdbc/with-db-connection cfg
          (migrate! cfg)))
      (reset! templates-created true))))

(def ^:private test-db-counter (atom 0))

(defn create-temp-db []
  "Creates a temporary test database.  Prefer with-test-db, etc."
  (ensure-pdb-db-templates-exist)
  (let [n (swap! test-db-counter inc)
        db-name (str "pdb_test_" n)]
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)
       (format "create database %s template pdb_test_template" db-name)))
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

(defn call-with-db-info-on-failure-or-drop
  "Calls (f), and then if there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  [db-config f]
  (let [before @clojure.test/*report-counters*]
    (try
      (f)
      (finally
        (let [after @clojure.test/*report-counters*]
          (if (and (= (:error before) (:error after))
                   (= (:fail before) (:fail after)))
            (let [db-name (subname->validated-db-name (:subname db-config))]
              (jdbc/with-db-connection (db-admin-config)
                (jdbc/do-commands
                 (format "alter database \"%s\" with connection limit 0" db-name)))
              (let [config (db-user-config "template1")]
                (jdbc/with-db-connection config
                  ;; We'll need this until we can upgrade bonecp (0.8.0
                  ;; appears to fix the problem).
                  (disconnect-db-user db-name (:user config))))
              (jdbc/with-db-connection (db-admin-config)
                (jdbc/do-commands-outside-txn
                 (format "drop database if exists %s" db-name))))
            (clojure.test/with-test-out
              (println "Leaving test database intact:" (:subname *db*)))))))))

(defmacro with-db-info-on-failure-or-drop
  "Evaluates body in the context of call-with-db-info-on-failure-or-drop."
  [db-config & body]
  `(call-with-db-info-on-failure-or-drop ~db-config (fn [] ~@body)))

(defn call-with-test-db
  "Binds *db* to a clean, migrated test database, opens a connection
  to it, and calls (f).  If there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
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

(defn insert-map [data]
  (apply (partial jdbc/insert! :test [:key :value]) data))

(defn call-with-antonym-test-database
  [function]
  (with-test-db
    (jdbc/with-db-transaction []
      (jdbc/do-commands
       (sql/create-table-ddl :test
                             [:key "VARCHAR(256)" "PRIMARY KEY"]
                             [:value "VARCHAR(256)" "NOT NULL"]))
      (insert-map antonym-data))
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
WHERE NOT nspname LIKE 'pg%';")

(defn db->index-map
  "Converts the metadata columns from their database names/formats to
  something more natural to use in Clojure"
  [row]
  (-> row
      (update :table #(.getValue %))
      (clojure.set/rename-keys {:is_unique :unique?
                                :is_functional :functional?
                                :is_primary :primary?})))

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

(defn query-indexes
  "Returns the list of all PuppetDB created indexes, sorted by table,
  then the name of the index"
  [db]
  (jdbc/with-db-connection db
    (sort-by (juxt :table :index)
             (map db->index-map (jdbc/query-to-vec indexes-sql)))))

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
      (group-by (juxt :table_name :column_name) tables))))

(defn schema-info-map [db-props]
  {:indexes (query-indexes db-props)
   :tables (query-tables db-props)})

(defn diff' [left right]
  (let [[left-only right-only same] (clojure.data/diff left right)]
    {:left-only left-only
     :right-only right-only
     :same same}))

(defn diff-table-maps [table-map-1 table-map-2]
  (let [table-names (set (concat (keys table-map-1) (keys table-map-2)))]
    (keep (fn [table-name]
            (let [{:keys [left-only right-only same]} (diff' (get table-map-1 table-name)
                                                             (get table-map-2 table-name))]
              (when (or left-only right-only)
                {:left-only (vec (remove nil? left-only))
                 :right-only (vec (remove nil? right-only))
                 :same (vec (remove nil? same))})))
          table-names)))

(defn diff-schema-maps [left right]
  (let [index-diff (diff' (:indexes left) (:indexes right))
        table-diffs (diff-table-maps (:tables left) (:tables right))]
    {:index-diff (when (or (:left-only index-diff) (:right-only index-diff))
                   (mapv (fn [left-item right-item same-item]
                           {:left-only left-item
                            :right-only right-item
                            :same same-item})
                         (:left-only index-diff) (:right-only index-diff) (:same index-diff)))
     :table-diff (when (seq table-diffs)
                   table-diffs)}))

(defn output-table-diffs [diff-list]
  (str/join "\n\n------------------------------\n\n"
            (remove nil?
                    (map (fn [{:keys [left-only right-only same]}]
                           (when (or left-only right-only)
                             (apply str ["Left Only:\n"
                                         (pprint-str left-only)
                                         "\nRight Only:\n"
                                         (pprint-str right-only)
                                         (str "\nSame:\n")
                                         (pprint-str same)])))
                         diff-list))))
