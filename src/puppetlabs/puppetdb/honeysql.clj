(ns puppetlabs.puppetdb.honeysql
  "Some generic HoneySQL extensions, candidates for re-usability and
  potential upstream submission."
  (:require [honeysql.core :as sql]
            [honeysql.format :as fmt]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s])
  (:import (honeysql.types SqlCall)))

(def key-or-sql
  "Either a honeysql call, or a keyword argument"
  (s/either SqlCall s/Keyword))

(pls/defn-validated coalesce :- SqlCall
  [& args :- [(s/one key-or-sql "Keyword") key-or-sql]]
  "coalesce(arg, ...) sql function"
  (apply sql/call :coalesce args))

(pls/defn-validated scast :- SqlCall
  [source :- s/Keyword
   target :- s/Keyword]
  "cast(source AS target) sql function"
  (sql/call :cast (sql/raw (str (fmt/to-sql source)
                                " AS "
                                (fmt/to-sql target)))))

(pls/defn-validated json-agg :- SqlCall
  [expr :- key-or-sql]
  "json_agg(expr) sql function"
  (sql/call :json_agg expr))

(pls/defn-validated row-to-json :- SqlCall
  [record :- key-or-sql]
  "row_to_json(record) sql function"
  (sql/call :row_to_json record))

(pls/defn-validated row :- SqlCall
  [& expr :- [(s/one key-or-sql "Keyword") key-or-sql]]
  "row(expr, ...) sql function"
  (apply sql/call :row expr))
