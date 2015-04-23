(ns puppetlabs.puppetdb.query.report-data
  (:require [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(pls/defn-validated munge-result-rows
  "Intended to be used for parsing the results
  from either the report :metrics or :logs queries."
  [data :- s/Keyword]
  (fn [rows]
    (if-let [maybe-json (-> rows first data)]
      (sutils/parse-db-json maybe-json)
      [])))

(pls/defn-validated logs-query->sql :- jdbc/valid-results-query-schema
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [_
   query :- (s/maybe [s/Any])
   & _]
  (qe/compile-user-query->sql qe/report-logs-query query {}))

(pls/defn-validated metrics-query->sql :- jdbc/valid-results-query-schema
  "Converts a vector-structured `query` to a corresponding SQL query which will
  return nodes matching the `query`."
  [_
   query :- (s/maybe [s/Any])
   & _]
  (qe/compile-user-query->sql qe/report-metrics-query query {}))
