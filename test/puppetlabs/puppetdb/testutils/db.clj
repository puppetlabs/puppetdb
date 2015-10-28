(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [clojure.string :as str]
            [environ.core :refer [env]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
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

(def ^:private templates-created (atom false))

(defn- ensure-pdb-db-templates-exist []
  (locking ensure-pdb-db-templates-exist
    (when-not @templates-created
      (jdbc/with-db-connection (db-admin-config)
        (let [conn (doto (:connection (jdbc/db)) (.setAutoCommit true))
              ex (fn [cmd] (-> conn .createStatement (.execute cmd)))]
          (ex (format "drop database if exists pdb_test_template"))
          (ex (format "create database pdb_test_template"))))
      (let [cfg (db-user-config "pdb_test_template")]
        (jdbc/with-db-connection cfg
          (migrate! cfg)))
      (reset! templates-created true))))

(def ^:private test-db-counter (atom 0))

(defn create-temp-db []
  (ensure-pdb-db-templates-exist)
  (let [n (swap! test-db-counter inc)
        db-name (str "pdb_test_" n)]
    (jdbc/with-db-connection (db-admin-config)
      (jdbc/do-commands-outside-txn
       (format "drop database if exists %s" db-name)
       (format "create database %s template pdb_test_template" db-name)))
    (db-user-config db-name)))

(def ^:dynamic *db-spec* nil)

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

(defn with-antonym-test-database
  [function]
  (let [db (create-temp-db)]
    (binding [*db-spec* db]
      (jdbc/with-db-connection db
        (jdbc/with-db-transaction []
          (jdbc/do-commands
           (sql/create-table-ddl :test
                                 [:key "VARCHAR(256)" "PRIMARY KEY"]
                                 [:value "VARCHAR(256)" "NOT NULL"]))
          (insert-map antonym-data))
        (function)))))

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
