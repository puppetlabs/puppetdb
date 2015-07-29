(ns puppetlabs.puppetdb.query
  "SQL query compiler

   The query compile operates in a multi-step process. Compilation begins with
   one of the `foo-query->sql` functions. The job of these functions is
   basically to call `compile-term` on the first term of the query to get back
   the \"compiled\" form of the query, and then to turn that into a complete SQL
   query.

   The compiled form of a query consists of a map with two keys: `where`
   and `params`. The `where` key contains SQL for querying that
   particular predicate, written in such a way as to be suitable for placement
   after a `WHERE` clause in the database. `params` contains, naturally, the
   parameters associated with that SQL expression. For instance, a resource
   query for `[\"=\" [\"node\" \"name\"] \"foo.example.com\"]` will compile to:

       {:where \"catalogs.certname = ?\"
        :params [\"foo.example.com\"]}

   The `where` key is then inserted into a template query to return
   the final result as a string of SQL code.

   The compiled query components can be combined by operators such as
   `AND` or `OR`, which return the same sort of structure. Operators
   which accept other terms as their arguments are responsible for
   compiling their arguments themselves. To facilitate this, those
   functions accept as their first argument a map from operator to
   compile function. This allows us to have a different set of
   operators for resources and facts, or queries, while still sharing
   the implementation of the operators themselves.

   Other operators include the subquery operators, `in`, `extract`, and
   `select-resources` or `select-facts`. The `select-foo` operators implement
   subqueries, and are simply implemented by calling their corresponding
   `foo-query->sql` function, which means they return a complete SQL query
   rather than the compiled query map. The `extract` function knows how to
   handle that, and is the only place those queries are allowed as arguments.
   `extract` is used to select a particular column from the subquery. The
   sibling operator to `extract` is `in`, which checks that the value of
   a certain column from the table being queried is in the result set returned
   by `extract`. Composed, these three operators provide a complete subquery
   facility. For example, consider this fact query:

       [\"and\"
        [\"=\" [\"fact\" \"name\"] \"ipaddress\"]
        [\"in\" \"certname\"
         [\"extract\" \"certname\"
          [\"select-resources\" [\"and\"
                               [\"=\" \"type\" \"Class\"]
                               [\"=\" \"title\" \"apache\"]]]]]]

   This will perform a query (via `select-resources`) for resources matching
   `Class[apache]`. It will then pick out the `certname` from each of those,
   and match against the `certname` of fact rows, returning those facts which
   have a corresponding entry in the results of `select-resources` and which
   are named `ipaddress`. Effectively, the semantics of this query are \"find
   the ipaddress of every node with Class[apache]\".

   The resulting SQL from the `foo-query->sql` functions selects all the
   columns. Thus consumers of those functions may need to wrap that query with
   another `SELECT` to pull out only the desired columns. Similarly for
   applying ordering constraints."
  (:require [clojure.string :as str]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.honeysql :as h]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.kitchensink.core :refer [parse-number keyset valset order-by-expr?]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils
             :refer [db-serialize sql-as-numeric sql-array-query-string
                     legacy-sql-regexp-match sql-regexp-array-match]]
            [puppetlabs.puppetdb.jdbc
             :refer [valid-jdbc-query? limited-query-to-vec query-to-vec paged-sql count-sql get-result-count]]
            [puppetlabs.puppetdb.query.paging :refer [requires-paging?]]
            [clojure.core.match :refer [match]]
            [puppetlabs.puppetdb.utils :as utils]))

(defn execute-paged-query*
  "Helper function to executed paged queries.  Builds up the paged sql string,
  executes the query, and returns map containing the `:result` key and an
  optional `:count` key."
  [fail-limit query {:keys [limit offset order_by count?] :as paging-options}]
  {:pre [(and (integer? fail-limit) (>= fail-limit 0))
         (valid-jdbc-query? query)
         ((some-fn nil? integer?) limit)
         ((some-fn nil? integer?) offset)
         ((some-fn nil? sequential?) order_by)
         (every? order-by-expr? order_by)]
   :post [(map? %)
          (vector? (:result %))
          ((some-fn nil? integer?) (:count %))]}
  (let [[sql & params] (if (string? query) [query] query)
        paged-sql      (paged-sql sql paging-options)
        result         {:result
                        (limited-query-to-vec
                         fail-limit
                         (apply vector paged-sql params))}]
    ;; TODO: this could also be implemented using `COUNT(*) OVER()`,
    ;; which would allow us to get the results and the count via a
    ;; single query (rather than two separate ones).  Need to do
    ;; some benchmarking to see which is faster.
    (if count?
      (assoc result :count
             (get-result-count (apply vector (count-sql sql) params)))
      result)))

(defn execute-query*
  "Helper function to executed non-paged queries.  Returns a map containing the
  `:result` key."
  [fail-limit query]
  {:pre [(and (integer? fail-limit) (>= fail-limit 0))
         (valid-jdbc-query? query)]
   :post [(map? %)
          (vector? (:result %))]}
  {:result (limited-query-to-vec fail-limit query)})

(defn execute-query
  "Given a query and a map of paging options, adds the necessary SQL for
  implementing the paging, executes the query, and returns a map containing
  the results and metadata.

  The return value will contain a key `:result`, whose value is a vector of
  the query results.  If the paging options indicate that a 'total record
  count' should be returned, then the map will also include a key `:count`,
  whose value is an integer indicating the total number of results available."
  ([query paging-options] (execute-query 0 query paging-options))
  ([fail-limit query {:keys [limit offset order_by] :as paging-options}]
     {:pre [((some-fn string? sequential?) query)]
      :post [(map? %)
             (vector? (:result %))
             ((some-fn nil? integer?) (:count %))]}
     (let [sql-and-params (if (string? query) [query] query)]
       (if (requires-paging? paging-options)
         (execute-paged-query* fail-limit sql-and-params paging-options)
         (execute-query* fail-limit sql-and-params)))))

(defn compile-term
  "Compile a single query term, using `ops` as the set of legal operators. This
  function basically just checks that the operator is known, and then
  dispatches to the function implementing it."
  [ops [op & args :as term]]

  (cond
    (empty? term)
    {:where nil :params nil}

    (not op)
    (throw (IllegalArgumentException. (format "%s is not well-formed: queries must contain at least one operator" (vec term))))

    :else
    (if-let [f (ops op)]
      (apply f args)
      (throw (IllegalArgumentException. (format "%s is not well-formed: query operator '%s' is unknown" (vec term) op))))))

(defn compile-boolean-operator*
  "Compile a term for the boolean operator `op` (AND or OR) applied to
  `terms`. This is accomplished by compiling each of the `terms` and then just
  joining their `where` terms with the operator. The params are just
  concatenated."
  [op ops & terms]
  {:pre  [(every? coll? terms)]
   :post [(string? (:where %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [compiled-terms (map #(compile-term ops %) terms)
        params (mapcat :params compiled-terms)
        query  (->> (map :where compiled-terms)
                    (map #(format "(%s)" %))
                    (str/join (format " %s " (str/upper-case op))))]
    {:where  query
     :params params}))

(def compile-and
  (partial compile-boolean-operator* "and"))

(def compile-or
  (partial compile-boolean-operator* "or"))

(defn negate-term*
  "Compiles `term` and returns the negated version of the query."
  [ops term]
  {:pre  [(sequential? term)]
   :post [(string? (:where %))]}
  (let [compiled-term (compile-term ops term)
        query (format "NOT (%s)" (:where compiled-term))]
    (assoc compiled-term :where query)))

(defn compile-not
  "Compile a NOT operator, applied to `term`. This term simply negates the
  value of `term`. Basically this function just serves as error checking for
  `negate-term*`."
  [version ops & terms]
  {:post [(string? (:where %))]}
  (when-not (= (count terms) 1)
    (throw (IllegalArgumentException. (format "'not' takes exactly one argument, but %d were supplied" (count terms)))))
  (negate-term* ops (first terms)))

;; This map's keys are the queryable fields for facts, and the values are the
;;  corresponding table names where the fields reside
(def fact-columns {"certname"         "facts"
                   "name"             "facts"
                   "value"            "facts"
                   "environment"      "facts"})

;; This map's keys are the queryable fields for factsets, and the values are the
;;  corresponding table names where the fields reside
(def factset-columns {"certname" "factsets"
                      "environment" "factsets"
                      "timestamp" "factsets"
                      "producer_timestamp" "factsets"
                      "hash" "factsets"})

;; This map's keys are the queryable fields for resources, and the values are the
;;  corresponding table names where the fields reside
(def resource-columns {"certname"   "catalogs"
                       "environment" "catalog_resources"
                       "catalog"    "catalog_resources"
                       "resource"   "catalog_resources"
                       "type"       "catalog_resources"
                       "title"      "catalog_resources"
                       "tags"       "catalog_resources"
                       "exported"   "catalog_resources"
                       "file"       "catalog_resources"
                       "line"       "catalog_resources"})

(def event-columns
  {"certname"               ["reports"]
   "configuration_version"  ["reports"]
   "start_time"             ["reports" "run_start_time"]
   "end_time"               ["reports" "run_end_time"]
   "receive_time"           ["reports" "report_receive_time"]
   "hash"                   ["reports" "report"]
   "status"                 ["resource_events"]
   "timestamp"              ["resource_events"]
   "resource_type"          ["resource_events"]
   "resource_title"         ["resource_events"]
   "property"               ["resource_events"]
   "new_value"              ["resource_events"]
   "old_value"              ["resource_events"]
   "message"                ["resource_events"]
   "file"                   ["resource_events"]
   "line"                   ["resource_events"]
   "containment_path"       ["resource_events"]
   "containing_class"       ["resource_events"]
   "name"                   ["environments" "environment"]})

(def resource-event-columns
  {"certname"               ["latest_events"]
   "configuration_version"  ["latest_events"]
   "run_start_time"         ["latest_events"]
   "run_end_time"           ["latest_events"]
   "report_receive_time"    ["latest_events"]
   "hash"                   ["latest_events" "report"]
   "status"                 ["latest_events"]
   "timestamp"              ["latest_events"]
   "resource_type"          ["latest_events"]
   "resource_title"         ["latest_events"]
   "property"               ["latest_events"]
   "new_value"              ["latest_events"]
   "old_value"              ["latest_events"]
   "message"                ["latest_events"]
   "file"                   ["latest_events"]
   "line"                   ["latest_events"]
   "containment_path"       ["latest_events"]
   "containing_class"       ["latest_events"]
   "name"                   ["latest_events" "environment"]})

(def report-columns
  "Return the queryable set of fields and corresponding table names where they reside"
  {"hash"                  "reports"
   "certname"              "reports"
   "puppet_version"        "reports"
   "report_format"         "reports"
   "configuration_version" "reports"
   "start_time"            "reports"
   "end_time"              "reports"
   "producer_timestamp"    "reports"
   "receive_time"          "reports"
   "transaction_uuid"      "reports"
   "environment"           "reports"
   "status"                "reports"})

(defn qualified-column
  "given a field and one of the column maps above, produce the fully qualified
   column name"
  [field columns]
  (let [source-table (first (utils/vector-maybe (get columns field)))]
    (format "%s.%s" source-table field)))

(defn column-map->sql
  "Helper function that converts one of our column maps to a SQL string suitable
  for use in a SELECT"
  [col-map]
  (str/join ", "
            (for [[field table] col-map]
              (str table "." field))))

(defmulti queryable-fields
  "This function takes a query type (:resource, :fact, :node) and a query
   API version number, and returns a set of strings which are the names the
   fields that are legal to query"
  (fn [query-type query-api-version] query-type))

(defmethod queryable-fields :resource
  [_ query-api-version]
  (keyset resource-columns))

(defmethod queryable-fields :fact
  [_ _]
  (keyset fact-columns))

(defmethod queryable-fields :event
  [_ _]
  (keyset event-columns))

(defmethod queryable-fields :report
  [_ _]
  (keyset report-columns))

(def subquery->type
  {"select_resources" :resource
   "select_facts"     :fact})

(defn compile-extract
  "Compile an `extract` operator, selecting the given `field` from the compiled
  result of `subquery`, which must be a kind of `select` operator."
  [query-api-version ops field subquery]
  {:pre [(or (string? field) (vector? field))
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (let [[subselect & params] (compile-term ops subquery)
        subquery-type (subquery->type (first subquery))]
    (when-not subquery-type
      (throw (IllegalArgumentException. (format "The argument to extract must be a select operator, not '%s'" (first subquery)))))
    (when-not (get (queryable-fields subquery-type query-api-version) field)
      (throw (IllegalArgumentException. (format "Can't extract unknown %s field '%s'. Acceptable fields are: %s" (name subquery-type) field (str/join ", " (sort (queryable-fields subquery-type query-api-version)))))))
    {:where (format "SELECT r1.%s FROM (%s) r1" field subselect)
     :params params}))

(defn compile-in
  "Compile an `in` operator, selecting rows for which the value of
  `field` appears in the result given by `subquery`, which must be an `extract`
  composed with a `select`."
  [kind query-api-version ops field subquery]
  {:pre [(or (string? field) (vector? field))
         (coll? subquery)]
   :post [(map? %)
          (string? (:where %))]}
  (when-not (get (queryable-fields kind query-api-version) field)
    (throw (IllegalArgumentException.
             (format "Can't match on unknown %s field '%s' for 'in'. Acceptable fields are: %s"
                     (name kind) field (str/join ", " (sort (queryable-fields kind query-api-version)))))))
  (when-not (= (first subquery) "extract")
    (throw (IllegalArgumentException.
             (format "The subquery argument of 'in' must be an 'extract', not '%s'" (first subquery)))))
  (let [{:keys [where] :as compiled-subquery} (compile-term ops subquery)
        columns (case kind
                  :fact fact-columns
                  :resource resource-columns
                  :event resource-event-columns
                  :report report-columns
                  :factset factset-columns)
        qualified-field (qualified-column field columns)]
    (assoc compiled-subquery
           :where (format "%s IN (%s)" qualified-field where))))

(defn resource-query->sql
  "Compile a resource query, returning a vector containing the SQL and
  parameters for the query. All resource columns are selected, and no order is applied."
  [ops query]
  {:post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s
                       FROM (SELECT %s as catalog, e.name as environment, catalog_id, resource,
                                    type, title, tags, exported, file, line
                             FROM catalog_resources cr, catalogs c LEFT OUTER JOIN environments e
                                  on c.environment_id = e.id
                             WHERE c.id = cr.catalog_id) AS catalog_resources
                       JOIN catalogs ON catalog_resources.catalog_id = catalogs.id
                     WHERE %s"
                    (column-map->sql resource-columns)
                    (sutils/sql-hash-as-str "c.hash")
                    where)]
    (apply vector sql params)))

(defn fact-query->sql
  "Compile a fact query, returning a vector containing the SQL and parameters
  for the query. All fact columns are selected, and no order is applied."
  [ops query]
  {:post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term ops query)
        sql (format "SELECT %s FROM (
                      SELECT fs.certname,
                             fp.name as name,
                             fv.value,
                             env.name as environment
                      FROM factsets fs
                        INNER JOIN facts as f on fs.id = f.factset_id
                        INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                        INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                        LEFT OUTER JOIN environments as env on fs.environment_id = env.id
                      WHERE depth = 0) AS facts
                    WHERE %s" (column-map->sql fact-columns) where)]
    (apply vector sql params)))

(defn certname-names-query [active]
  (if active
    "SELECT name FROM certnames WHERE deactivated IS NULL AND expired IS NULL"
    "SELECT name FROM certnames WHERE deactivated IS NOT NULL OR expired IS NOT NULL") )

(defn compile-resource-equality
  "Compile an = operator for a resource query. `path` represents the field
  to query against, and `value` is the value."
  [version & [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (match [path]
         ;; tag join. Tags are case-insensitive but always lowercase, so
         ;; lowercase the query value.
         ["tag"]
         {:where  (sql-array-query-string "tags")
          :params [(str/lower-case value)]}

         ;; node join.
         ["certname"]
         {:where  "catalogs.certname = ?"
          :params [value]}

         ["environment"]
         {:where  "catalog_resources.environment = ?"
          :params [value]}

         ;; {in,}active nodes.
         [["node" "active"]]
         {:where (format "catalogs.certname IN (%s)" (certname-names-query value))}

         ;; param joins.
         [["parameter" (name :guard string?)]]
         {:where  "catalog_resources.resource IN (SELECT rp.resource FROM resource_params rp WHERE rp.name = ? AND rp.value = ?)"
          :params [name (db-serialize value)]}

         ;; metadata match.
         [(metadata :guard #{"catalog" "resource" "type" "title" "tags" "exported" "file" "line"})]
         {:where  (format "catalog_resources.%s = ?" metadata)
          :params [value]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                       (format "'%s' is not a queryable object for resources in the version %s API" path (last (name version)))))))

(defn compile-resource-regexp
  "Compile an '~' predicate for a resource query, which does regexp matching.
  This is done by leveraging the correct database-specific regexp syntax to
  return only rows where the supplied `path` match the given `pattern`."
  [version & [path value :as args]]
  {:post [(map? %)
          (:where %)]}
  (match [path]
         ["tag"]
         {:where (h/sqlraw->str
                   (sql-regexp-array-match "catalog_resources"
                                           "catalog_resources"
                                           "tags"))
          :params [value]}

         ;; node join.
         ["certname"]
         {:where (legacy-sql-regexp-match "catalogs.certname")
          :params [value]}

         ["environment"]
         {:where (legacy-sql-regexp-match "catalog_resources.environment")
          :params [value]}

         ;; metadata match.
         [(metadata :guard #{"type" "title" "exported" "file"})]
         {:where (legacy-sql-regexp-match (format "catalog_resources.%s" metadata))
          :params [value]}

         ;; ...else, failure
         :else (throw (IllegalArgumentException.
                       (format "'%s' cannot be the target of a regexp match for version %s of the resources API" path (last (name version)))))))

(defn compile-fact-equality
  "Compile an = predicate for a fact query. `path` represents the field to
  query against, and `value` is the value."
  [version]
  (fn [path value]
    {:post [(map? %)
            (:where %)]}
    (match [path]
           ["name"]
           {:where "facts.name = ?"
            :params [value]}

           ["value"]
           {:where "facts.value = ? and depth = 0"
            :params [(str value)]}

           ["certname"]
           {:where "facts.certname = ?"
            :params [value]}

           ["environment"]
           {:where "facts.environment = ?"
            :params [value]}

           [["node" "active"]]
           {:where (format "facts.certname IN (%s)" (certname-names-query value))}

           :else
           (throw (IllegalArgumentException.
                   (format "%s is not a queryable object for version %s of the facts query api"
                           path (last (name version))))))))

(defn compile-fact-regexp
  "Compile an '~' predicate for a fact query, which does regexp matching.  This
  is done by leveraging the correct database-specific regexp syntax to return
  only rows where the supplied `path` match the given `pattern`."
  [version]
  (fn [path pattern]
    {:pre [(string? path)
           (string? pattern)]
     :post [(map? %)
            (string? (:where %))]}
    (let [query (fn [col] {:where (legacy-sql-regexp-match col)
                           :params [pattern]})]
      (match [path]
             ["certname"]
             (query "facts.certname")

             ["environment"]
             (query "facts.environment")

             ["name"]
             (query "facts.name")

             ["value"]
             {:where (format "%s and depth = 0"
                             (legacy-sql-regexp-match "facts.value"))
              :params [pattern]}

             :else (throw (IllegalArgumentException.
                           (format "%s is not a valid version %s operand for regexp comparison" path (last (name version)))))))))

(defn compile-fact-inequality
  "Compile a numeric inequality for a fact query (> < >= <=). The `value` for
  comparison must be either a number or the string representation of a number.
  The value in the database will be cast to a float or an int for comparison,
  or will be NULL if it is neither."
  [op path value]
  {:pre [(string? path)]
   :post [(map? %)
          (string? (:where %))]}
  (if-let [number (parse-number (str value))]
    (match [path]
           ["value"]
           ;; This is like convert_to_numeric(facts.value) > 0.3
           {:where  (format "%s %s ? and depth = 0" (sql-as-numeric "facts.value") op)
            :params [number]}

           :else (throw (IllegalArgumentException.
                         (str path " is not a queryable object for facts"))))
    (throw (IllegalArgumentException.
            (format "Value %s must be a number for %s comparison." value op)))))

(defn compile-resource-event-inequality
  "Compile a timestamp inequality for a resource event query (> < >= <=).
  The `value` for comparison must be coercible to a timestamp via
  `puppetlabs.puppetdb.time/to-timestamp` (e.g., an ISO-8601 compatible date-time string)."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count
                                                                                                                 args))))))

  (let [timestamp-fields {"timestamp"           "resource_events.timestamp"
                          "run_start_time"      "latest_events.run_start_time"
                          "run_end_time"        "latest_events.run_end_time"
                          "report_receive_time" "latest_events.report_receive_time"}]
    (match [path]
           [(field :guard (kitchensink/keyset timestamp-fields))]
           (if-let [timestamp (to-timestamp value)]
             {:where (format "%s %s ?" (timestamp-fields field) op)
              :params [(to-timestamp value)]}
             (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))

           :else (throw (IllegalArgumentException.
                         (str op " operator does not support object '" path "' for resource events"))))))

(defn compile-resource-event-equality
  "Compile an = predicate for resource event query. `path` represents the field to
  query against, and `value` is the value."
  [version]
  (fn [& [path value :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (utils/dashes->underscores path)]
      (match [path]
             ["certname"]
             {:where "latest_events.certname = ?"
              :params [value]}

             ["report"]
             {:where (format "%s = ?" (sutils/sql-hash-as-str "latest_events.hash"))
              :params [value]}

             ["latest_report?"]
             {:where (format "latest_report.report_id %s (SELECT certnames.latest_report FROM certnames)"
                             (if value "IN" "NOT IN"))}

             ["environment"]
             {:where "latest_events.name = ?"
              :params [value]}

             [(field :guard #{"report" "resource_type" "resource_title" "status"})]
             {:where (format "latest_events.%s = ?" field)
              :params [value] }

             ;; these fields allow NULL, which causes a change in semantics when
             ;; wrapped in a NOT(...) clause, so we have to be very explicit
             ;; about the NULL case.
             [(field :guard #{"property" "message" "file" "line" "containing_class"})]
             (if-not (nil? value)
               {:where (format "latest_events.%s = ? AND latest_events.%s IS NOT NULL" field field)
                :params [value] }
               {:where (format "latest_events.%s IS NULL" field)
                :params nil })

             ;; these fields require special treatment for NULL (as described above),
             ;; plus a serialization step since the values can be complex data types
             [(field :guard #{"old_value" "new_value"})]
             {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
              :params [(db-serialize value)] }

             :else (throw (IllegalArgumentException.
                           (format "'%s' is not a queryable object for version %s of the resource events API" path (last (name version)))))))))

(defn compile-resource-event-regexp
  "Compile an ~ predicate for resource event query. `path` represents the field
   to query against, and `pattern` is the regular expression to match."
  [version]
  (fn [& [path pattern :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException.
               (format "~ requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (utils/dashes->underscores path)]
      (match [path]
             ["certname"]
             {:where (legacy-sql-regexp-match "latest_events.certname")
              :params [pattern]}

             ["environment"]
             {:where (legacy-sql-regexp-match "latest_events.name")
              :params [pattern]}

             [(field :guard #{"report" "resource_type" "resource_title" "status"})]
             {:where  (legacy-sql-regexp-match (format "resource_events.%s" field))
              :params [pattern] }

             ;; these fields allow NULL, which causes a change in semantics when
             ;; wrapped in a NOT(...) clause, so we have to be very explicit
             ;; about the NULL case.
             [(field :guard #{"property" "message" "file" "line" "containing_class"})]
             {:where (format "%s AND latest_events.%s IS NOT NULL"
                             (legacy-sql-regexp-match (format "latest_events.%s" field))
                             field)
              :params [pattern]}

             :else (throw (IllegalArgumentException.
                           (format "'%s' is not a queryable object for version %s of the resource events API" path (last (name version)))))))))

(defn compile-event-count-equality
  "Compile an = predicate for event-count query.  The `path` represents
  the field to query against, and `value` is the value of the field."
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (let [db-field (utils/dashes->underscores path)]
    (match [db-field]
           [(field :guard #{"successes" "failures" "noops" "skips"})]
           {:where (format "%s = ?" field)
            :params [value]}

           :else (throw (IllegalArgumentException. (str path " is not a queryable object for event counts"))))))

(defn compile-event-count-inequality
  "Compile an inequality for an event-counts query (> < >= <=).  The `path`
  represents the field to query against, and the `value` is the value of the field."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
         [(field :guard #{"successes" "failures" "noops" "skips"})]
         {:where (format "%s %s ?" field op)
          :params [value]}

         :else (throw (IllegalArgumentException. (format "%s operator does not support object '%s' for event counts" op path)))))

(declare fact-operators)

(defn resource-operators
  "Maps resource query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (fn [op]
    (condp = (str/lower-case op)
      "=" (partial compile-resource-equality version)
      "~" (partial compile-resource-regexp version)
      "and" (partial compile-and (resource-operators version))
      "or" (partial compile-or (resource-operators version))
      "not" (partial compile-not version (resource-operators version))
      "extract" (partial compile-extract version (resource-operators version))
      "in" (partial compile-in :resource version (resource-operators version))
      "select_resources" (partial resource-query->sql (resource-operators version))
      "select_facts" (partial fact-query->sql (fact-operators version))
      nil)))

(defn fact-operators
  "Maps fact query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (fn [op]
    (let [op (str/lower-case op)]
      (cond
        (#{">" "<" ">=" "<="} op)
        (partial compile-fact-inequality op)

        (= op "=") (compile-fact-equality version)
        (= op "~") (compile-fact-regexp version)
        ;; We pass this function along so the recursive calls know which set of
        ;; operators/functions to use, depending on the API version.
        (= op "and") (partial compile-and (fact-operators version))
        (= op "or") (partial compile-or (fact-operators version))
        (= op "not") (partial compile-not version (fact-operators version))
        (= op "extract") (partial compile-extract version (fact-operators version))
        (= op "in") (partial compile-in :fact version (fact-operators version))
        (= op "select_resources") (partial resource-query->sql (resource-operators version))
        (= op "select_facts") (partial fact-query->sql (fact-operators version))))))

(defn resource-event-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [version]
  (fn [op]
    (let [op (str/lower-case op)]
      (cond
       (= op "=") (compile-resource-event-equality version)
       (= op "and") (partial compile-and (resource-event-ops version))
       (= op "or") (partial compile-or (resource-event-ops version))
       (= op "not") (partial compile-not version (resource-event-ops version))
       (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op)
       (= op "~") (compile-resource-event-regexp version)
       (= op "extract") (partial compile-extract version (resource-event-ops version))
       (= op "in") (partial compile-in :event version (resource-event-ops version))
       (= op "select_resources") (partial resource-query->sql (resource-operators version))
       (= op "select_facts") (partial fact-query->sql (fact-operators version))))))

(defn event-count-ops
  "Maps resource event count operators to the functions implementing them.
  Returns nil if the operator is unknown."
  [op]
  (let [op (str/lower-case op)]
    (cond
     (= "=" op) compile-event-count-equality
     (#{">" "<" ">=" "<="} op) (partial compile-event-count-inequality op))))
