;; ## Fact query generation

(ns com.puppetlabs.puppetdb.query.facts
  (:require [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query-eng :as qe]))

(defn flat-facts-by-node
  "Similar to `facts-for-node`, but returns facts in the form:

    [{:certname <node> :name <fact> :value <value>}
     ...
     {:certname <node> :name <fact> :value <value>}]"
  [node]
  (jdbc/query-to-vec
    ["SELECT certname, name, value FROM certname_facts WHERE certname = ?"
     node]))

(defn fact-names
  "Returns the distinct list of known fact names, ordered alphabetically
  ascending. This includes facts which are known only for deactivated nodes."
  ([]
    (fact-names {}))
  ([paging-options]
    {:post [(map? %)
            (coll? (:result %))
            (every? string? (:result %))]}
    (paging/validate-order-by! [:name] paging-options)
    (let [facts (query/execute-query
                  ["SELECT DISTINCT name FROM certname_facts ORDER BY name"]
                  paging-options)]
      (update-in facts [:result] #(map :name %)))))

(defn facts-sql
  "Return a vector with the facts SQL query string as the first element, parameters
   needed for that query as the rest."
  [operators query paging-options]
  (if query
    (let [[subselect & params] (query/fact-query->sql operators query)
          sql (format "SELECT facts.certname, facts.environment, facts.name, facts.value FROM (%s) facts" subselect)]
      (apply vector sql params))
    ["SELECT certname, name, value FROM certname_facts"]))

(defn query->sql
  "Compile a query into an SQL expression."
  [version query paging-options]
  {:pre [((some-fn nil? sequential?) query) ]
   :post [(map? %)
          (string? (first (:results-query %)))
          (every? (complement coll?) (rest (:results-query %)))]}
  (paging/validate-order-by! (map keyword (keys query/fact-columns)) paging-options)
  (case version
    (:v2 :v3)
    (let [operators (query/fact-operators version)
          [sql & params] (facts-sql operators query paging-options)]
      (conj {:results-query (apply vector (jdbc/paged-sql sql paging-options) params)}
            (when (:count? paging-options)
              [:count-query (apply vector (jdbc/count-sql sql) params)])))

    (qe/compile-user-query->sql
     qe/facts-query query paging-options)))
