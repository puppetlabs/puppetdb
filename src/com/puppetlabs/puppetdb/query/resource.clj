;; ## Resource querying
;;
;; This implements resource querying, using the query compiler in
;; `com.puppetlabs.puppetdb.query`, basically by munging the results into the
;; right format and picking out the desired columns.
;;
(ns com.puppetlabs.puppetdb.query.resource
  (:require [cheshire.core :as json]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [with-query-results-cursor valid-jdbc-query?]]
        [com.puppetlabs.puppetdb.query :only [resource-query->sql resource-operators-v1 resource-operators-v2]]))

(defn query->sql
  "Compile a resource `query` into an SQL expression using the specified set of
  `operators`."
  [operators query]
  {:pre  [(sequential? query)]
   :post [(valid-jdbc-query? %)]}
  (let [[subselect & params] (resource-query->sql operators query)
        sql (format (str "SELECT subquery1.certname, subquery1.resource, subquery1.type, subquery1.title, subquery1.tags, subquery1.exported, subquery1.sourcefile, subquery1.sourceline, rpc.parameters "
                         "FROM (%s) subquery1 "
                         "LEFT OUTER JOIN resource_params_cache rpc ON rpc.resource = subquery1.resource")
                    subselect)]
    (apply vector sql params)))

(def v1-query->sql
  (partial query->sql resource-operators-v1))

(def v2-query->sql
  (partial query->sql resource-operators-v2))

(defn with-queried-resources
  [query params func]
  (let [parse-params #(if % (json/parse-string %) {})]
    (with-query-results-cursor query params rs
      (let [resource-seq (map #(update-in % [:parameters] parse-params) rs)]
        (func resource-seq)))))

(defn query-resources
  "Take a query and its parameters, and return a vector of resources
   and their parameters which match."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [results (atom [])]
    (with-queried-resources sql params #(reset! results (vec %)))
    @results))
