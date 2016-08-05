(ns puppetlabs.puppetdb.honeysql
  "Some generic HoneySQL extensions, candidates for re-usability and
  potential upstream submission."
  (:require [honeysql.core :as hcore]
            [honeysql.format :as hfmt]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s])
  (:import [honeysql.types SqlCall SqlRaw]))

;; SCHEMA

(def key-or-sql
  "Either a honeysql call, or a keyword argument"
  (s/cond-pre SqlCall SqlRaw s/Keyword))

;; COMMMON DIRECT SQL FUNCTIONS

(pls/defn-validated coalesce :- SqlCall
  "coalesce(arg, ...) sql function"
  [& args :- [(s/one key-or-sql "Keyword") key-or-sql]]
  (apply hcore/call :coalesce args))

(pls/defn-validated scast :- SqlCall
  "cast(source AS target) sql function"
  [source :- s/Keyword
   target :- s/Keyword]
  (hcore/call :cast source target))

(pls/defn-validated json-agg :- SqlCall
  "json_agg(expr) sql function"
  [expr :- key-or-sql]
  (hcore/call :json_agg expr))

(pls/defn-validated json-object-agg :- SqlCall
  [& args]
  (apply hcore/call :json_object_agg args))

(pls/defn-validated row-to-json :- SqlCall
  "row_to_json(record) sql function"
  [record :- key-or-sql]
  (hcore/call :row_to_json record))

(pls/defn-validated row :- SqlCall
  "row(expr, ...) sql function"
  [& expr :- [(s/one key-or-sql "Keyword") key-or-sql]]
  (apply hcore/call :row expr))

(pls/defn-validated regexp-substring :- SqlCall
  "regexp_substr(expr, regexp) sql function"
  [expr :- key-or-sql
   regexp :- s/Str]
  (hcore/call :regexp_substring expr regexp))

(pls/defn-validated unnest :- SqlCall
  "unnest(expr) sql function"
  [expr :- key-or-sql]
  (hcore/call :unnest expr))

;; Misc honeysql functions

(defn raw? [x]
  (instance? honeysql.types.SqlRaw x))

(defn extract-sql
  "Returns a keyworidzed version of the SQL string if `k` is a SqlRaw, otherwise returns `s`"
  [s]
  (if-let [^SqlRaw raw-sql (and (raw? s) s)]
    (keyword (.s raw-sql))
    s))

(defn sqlraw->str
  [^SqlRaw raw-sql]
  (.s raw-sql))

;; NEW OPERATORS

; Custom formatter for PostgreSQL's ~ operator
(defmethod hfmt/fn-handler "~" [_ a b & more]
  (if (seq more)
    (apply hfmt/expand-binary-ops "~" a b more)
    (str (hfmt/to-sql a) " ~ " (hfmt/to-sql b))))
