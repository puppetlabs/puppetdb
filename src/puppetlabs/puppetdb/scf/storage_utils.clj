(ns puppetlabs.puppetdb.scf.storage-utils
  (:require [cheshire.factory :refer [*json-factory*]]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [honeysql.core :as hcore]
            [honeysql.format :as hfmt]
            [clojure.string :as str]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]
            [puppetlabs.i18n.core :refer [trs]]
            [clojure.string :as string])
  (:import [java.sql Connection]
           [java.util UUID]
           [org.postgresql.util PGobject]))

;; SCHEMA

(defn array-to-param
  [col-type java-type values]
  (.createArrayOf ^Connection (:connection (jdbc/db))
                  col-type
                  (into-array java-type values)))

(def pg-extension-map
  "Maps to the table definition in postgres, but only includes some of the
   columns:

     Table pg_catalog.pg_extension
   Column     |  Type   | Modifiers
   ----------------+---------+-----------
   extname        | name    | not null
   extrelocatable | boolean | not null
   extversion     | text    |"
  {:name s/Str
   :relocatable s/Bool
   :version (s/maybe s/Str)})

(def db-version
  "A list containing a major and minor version for the database"
  [s/Int])

(defn db-metadata []
  (let [db-metadata (.getMetaData ^Connection (:connection (jdbc/db)))]
    {:database (.getDatabaseProductName db-metadata)
     :version [(.getDatabaseMajorVersion db-metadata)
               (.getDatabaseMinorVersion db-metadata)]}))

(defn sql-current-connection-table-names
  "Returns the names of all of the tables in the public schema of the
  current connection's database.  This is most useful for debugging /
  testing purposes to allow introspection on the database.  (Some of
  our unit tests rely on this.)."
  []
  (let [query   "SELECT table_name FROM information_schema.tables WHERE LOWER(table_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :table_name results)))

(defn sql-current-connection-sequence-names
  "Returns the names of all of the sequences in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query   "SELECT sequence_name FROM information_schema.sequences WHERE LOWER(sequence_schema) = 'public'"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map :sequence_name results)))

(defn sql-current-connection-function-names
  "Returns the names of all of the functions in the public schema of
  the current connection's database.  This is most useful for
  debugging / testing purposes to allow introspection on the
  database.  (Some of our unit tests rely on this.)."
  []
  (let [query (str "SELECT pp.proname as name, pg_catalog.pg_get_function_arguments(pp.oid) as args "
                   "FROM pg_proc pp "
                   "INNER JOIN pg_namespace pn ON (pp.pronamespace = pn.oid) "
                   "INNER JOIN pg_language pl ON (pp.prolang = pl.oid) "
                   "WHERE pl.lanname NOT IN ('c') "
                   "AND pn.nspname NOT LIKE 'pg_%'"
                   "AND pn.nspname <> 'information_schema'")
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (map (fn [{:keys [name args]}] (str name "(" args ")"))
         results)))

(pls/defn-validated pg-installed-extensions :- {s/Str pg-extension-map}
  "Obtain the extensions installed and metadata about each extension for
   the current database."
  []
  (let [query "SELECT extname as name,
                      extversion as version,
                      extrelocatable as relocatable
               FROM pg_extension"
        results (jdbc/with-db-transaction [] (jdbc/query-to-vec query))]
    (zipmap (map :name results)
            results)))

(pls/defn-validated pg-extension? :- s/Bool
  "Returns true if the named PostgreSQL extension is installed."
  [extension :- s/Str]
  (let [extensions (pg-installed-extensions)]
    (not= (get extensions extension) nil)))

(defn current-schema
  "Returns the current schema of the database connection on postgres."
  []
  (->> (jdbc/query-to-vec "select current_schema")
       first
       :current_schema))

(pls/defn-validated index-exists? :- s/Bool
  "Returns true if the index exists. Only supported on PostgreSQL currently."
  ([index :- s/Str]
   (let [schema (current-schema)]
     (index-exists? index schema)))
  ([index :- s/Str
    namespace :- s/Str]
     (let [query "SELECT c.relname
                    FROM   pg_index as idx
                    JOIN   pg_class as c ON c.oid = idx.indexrelid
                    JOIN   pg_namespace as ns ON ns.oid = c.relnamespace
                    WHERE  ns.nspname = ?
                      AND  c.relname = ?"
           results (jdbc/query-to-vec [query namespace index])]
       (= (:relname (first results))
          index))))

(pls/defn-validated constraint-exists? :- s/Bool
  ([table :- s/Str
    constraint :- s/Str]
   (let [schema (current-schema)]
     (constraint-exists? schema table constraint)))

  ([schema :- s/Str
    table :- s/Str
    constraint :- s/Str]
   (let [query (str "select count(*)"
                    "  from information_schema.constraint_column_usage"
                    "  where table_schema = ?"
                    "  and table_name = ?"
                    "  and constraint_name = ?")
         results (jdbc/query-to-vec [query schema table constraint])]
     (pos? (:count (first results))))))

(defn to-jdbc-varchar-array
  "Takes the supplied collection and transforms it into a
  JDBC-appropriate VARCHAR array."
  [coll]
  (->> coll
       (into-array Object)
       (.createArrayOf ^Connection (:connection (jdbc/db)) "varchar")))

(defn legacy-sql-regexp-match
  "Returns the SQL for performing a regexp match."
  [column]
  (format "(%s ~ ? AND %s IS NOT NULL)" column column))

(defn sql-as-numeric
  "Returns the SQL for converting the given column to a number, or to
  NULL if it is not numeric."
  [column]
  (hcore/raw (format (str "CASE WHEN %s~E'^\\\\d+$' THEN %s::bigint "
                          "WHEN %s~E'^\\\\d+\\\\.\\\\d+$' THEN %s::float "
                          "ELSE NULL END")
                     column column column column)))

(defn sql-array-type-string
  "Returns the SQL to declare an array of the supplied base database
  type."
  [basetype]
  (format "%s ARRAY[1]" basetype))

(defn sql-array-query-string
  "Returns an SQL fragment representing a query for a single value
  being found in an array column in the database.

    (str \"SELECT ... WHERE \" (sql-array-query-string \"column_name\"))

  The returned SQL fragment will contain *one* parameter placeholder,
  which must be supplied as the value to be matched."
  [column]
  (hcore/raw
   (format "ARRAY[?::text] <@ %s" (name column))))

(defn sql-regexp-match
  "Returns db code for performing a regexp match."
  [column]
  [:and
   [(keyword "~") column "?"]
   [:is-not column nil]])

(defn sql-regexp-array-match
  "Returns SQL for performing a regexp match against the contents of
  an array. If any of the array's items match the supplied regexp,
  then that satisfies the match."
  [column]
  (hcore/raw
   (format "EXISTS(SELECT 1 FROM UNNEST(%s) WHERE UNNEST ~ ?)" (name column))))

(defn sql-cast
  [type]
  (fn [column]
    (if (= type "jsonb")
      (hcore/raw (format "to_jsonb(%s)" column))
      (hcore/raw (format "CAST(%s AS %s)" column type)))))

(defn jsonb-null?
  "A predicate determining whether the json types of a jsonb column are null."
  [column null?]
  (let [op (if null? "=" "<>")]
    (hcore/raw (format "jsonb_typeof(%s) %s 'null'" (name column) op))))

(defn sql-in-array
  [column]
  (hcore/raw
   (format "%s = ANY(?)" (first (hfmt/format column)))))

(defn json-contains
  [field array-in-path]
  (if array-in-path
    (hcore/raw (format "%s #> ? = ?" field))
    (hcore/raw (format "%s @> ?" field))))

(defn fn-binary-expression
  "Produce a predicate that compares the result of a function against a
   provided value."
  [op function args]
  (let [fargs (str/join ", " args)]
    (hcore/raw (format "%s(%s) %s ?" function fargs op))))

(defn jsonb-path-binary-expression
  "Produce a predicate that compares against nested value with op and checks the
  existence of a (presumably) top-level value. The existence check is necessary
  because -> is not indexable (with GIN) but ? is. Assumes a GIN index on the
  column supplied."
  [op column qmarks]
  (if (= "~" (name op))
    (let [path-elts (cons column qmarks)
          path (apply str
                      (str/join "->" (butlast path-elts))
                      (when-let [x (last path-elts)] ["->>" x]))]
      (hcore/raw (string/join \space
                              [(str "(" path ")") (name op) "(?#>>'{}')"
                               "and" column "??" "?"])))
    (let [delimited-qmarks (str/join "->" qmarks)]
      (hcore/raw (string/join \space
                              [(str "(" column "->" delimited-qmarks ")")
                               (name op) "?"
                               "and" column "??" "?"])))))

(defn jsonb-scalar-cast
  [typ]
  (fn
    [column]
    (hcore/raw (format "(%s#>>'{}')::%s" column typ))))

(defn jsonb-scalar-regex
  "Produce a predicate that matches a regex against a scalar jsonb value "
  [column]
  ;; This gets the unwrapped json value as text
  (hcore/raw (format "(%s#>>'{}')::text ~ ?" column)))

(defn db-serialize
  "Serialize `value` into a form appropriate for querying against a
  serialized database column."
  [value]
  (json/generate-string (kitchensink/sort-nested-maps value)))

(defn fix-identity-sequence
  "Resets a sequence to the maximum value used in a column. Useful when a
  sequence gets out of sync due to a bug or after a transfer."
  [table column]
  {:pre [(string? table)
         (string? column)]}
  (jdbc/with-db-transaction []
    (jdbc/do-commands (str "LOCK TABLE " table " IN ACCESS EXCLUSIVE MODE"))
    (jdbc/query-with-resultset
     [(format
       "select setval(pg_get_serial_sequence(?, ?), (select max(%s) from %s))"
       column table)
      table column]
     (constantly nil))))

(defn sql-hash-as-str
  [column]
  (format "encode(%s::bytea, 'hex')" column))

(defn vacuum-analyze
  [db]
  (sql/with-db-connection [conn db]
    (sql/execute! db ["vacuum analyze"] {:transaction? false})))

(defn parse-db-hash
  [^PGobject db-hash]
  (clojure.string/replace (.getValue db-hash) "\\x" ""))

(defn parse-db-uuid
  [^UUID db-uuid]
  (.toString db-uuid))

(pls/defn-validated parse-db-json
  "Produce a function for parsing an object stored as json."
  [^PGobject db-json :- (s/maybe (s/cond-pre s/Str PGobject))]
  (some-> db-json .getValue (json/parse-string true)))

(pls/defn-validated str->pgobject :- PGobject
  [type :- s/Str
   value]
  (doto (PGobject.)
    (.setType type)
    (.setValue value)))

(defn munge-uuid-for-storage
  [value]
  (str->pgobject "uuid" value))

(defn bytea-escape [s]
  (format "\\x%s" s))

(defn munge-hash-for-storage
  [hash]
  (str->pgobject "bytea" (bytea-escape hash)))

(defn munge-json-for-storage
  "Prepare a clojure object for storage depending on db type."
  [value]
  (let [json-str (json/generate-string value)]
    (str->pgobject "json" json-str)))

(defn munge-jsonb-for-storage
  "Prepare a clojure object for storage.  Rewrite all null (\\u0000)
  characters to the replacement character (\\ufffd) because Postgres
  cannot handle them in its JSON values."
  [value]
  (binding [*json-factory* json/null-replacing-json-factory]
    (str->pgobject "jsonb" (json/generate-string value))))

(defn db-up?
  [db-spec]
  (utils/with-timeout 1000 false
    (try
      (jdbc/with-transacted-connection db-spec
        (let [select-42 "SELECT (a - b) AS answer FROM (VALUES ((7 * 7), 7)) AS x(a, b)"
              [{:keys [answer]}] (jdbc/query [select-42])]
          (= answer 42)))
      (catch Exception _
        false))))

(defn analyze-small-tables
  [small-tables]
  (log/info (trs "Analyzing small tables"))
  (apply jdbc/do-commands-outside-txn
         (map #(str "analyze " %) small-tables)))

(defn handle-quoted-path-segment
  [v]
  (loop [result []
         [s & splits] v]
    (let [s-len (count s)]
      (if (and (str/ends-with? s "\"")
               (not (= s-len 1))
               (or (<= s-len 2) (not (= (nth s (- s-len 2)) \\))))
        [(str/join "." (conj result s)) splits]
        (recur (conj result s) splits)))))

(defn dotted-query->path
  [string]
  (loop [[s & splits :as v] (str/split string #"\.")
         result []]
    (if (nil? s)
      result
      (let [s-len (count s)]
        (if (and (str/starts-with? s "\"")
                 (or (= s-len 1)
                     (or (not (str/ends-with? s "\""))
                         (and (str/ends-with? s "\"")
                              (>= s-len 2)
                              (= (nth s (- s-len 2)) \\)))))
          (let [[x xs] (handle-quoted-path-segment v)]
            (recur xs (conj result x)))
          (recur splits (conj result s)))))))

(defn expand-array-access-in-path
  "Given a path like [\"a\" \"b[0]\" \"c\"], expand the [0] to get
   [\"a\" \"b\" 0 \"c\"]"
  [path]
  (mapcat (fn [el]
            (let [[[_ field index-str]] (re-seq #"^(.*)\[(\d+)\]$" el)]
              (if index-str
                [field (Integer/parseInt index-str)]
                [el])))
          path))
