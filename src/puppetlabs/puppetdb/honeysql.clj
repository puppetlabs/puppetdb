(ns puppetlabs.puppetdb.honeysql
  "Some generic HoneySQL extensions, candidates for re-usability and
  potential upstream submission."
  (:require [honeysql.core :as hcore]
            [honeysql.format :as hfmt]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s])
  (:import (honeysql.types SqlCall SqlRaw)))

;; SCHEMA
;(hcore/format {:where [:and ["~" :reports.certname "?"] [:is-not :reports.certname nil]]
;               :group-by status})
;
;(hcore/format {:where [:and ["~" :reports.certname "?"]  [:is-not :reports.certname nil]],
;               :group-by  '("status"),
;               :call  [:count :*],
;               :left-join
;               [:environments
;                [:= :environments.id :reports.environment_id]
;                :report_statuses
;                [:= :reports.status_id :report_statuses.id]],
;               :from  [:reports],
;               :select  [[:report_statuses.status "status"]
;                         (hcore/call :count :*)]})

(def key-or-sql
  "Either a honeysql call, or a keyword argument"
  (s/either SqlCall SqlRaw s/Keyword))

;; COMMMON DIRECT SQL FUNCTIONS

(pls/defn-validated coalesce :- SqlCall
  [& args :- [(s/one key-or-sql "Keyword") key-or-sql]]
  "coalesce(arg, ...) sql function"
  (apply hcore/call :coalesce args))

(pls/defn-validated scast :- SqlCall
  [source :- s/Keyword
   target :- s/Keyword]
  "cast(source AS target) sql function"
  (hcore/call :cast source target))

(pls/defn-validated json-agg :- SqlCall
  [expr :- key-or-sql]
  "json_agg(expr) sql function"
  (hcore/call :json_agg expr))

(pls/defn-validated row-to-json :- SqlCall
  [record :- key-or-sql]
  "row_to_json(record) sql function"
  (hcore/call :row_to_json record))

(pls/defn-validated row :- SqlCall
  [& expr :- [(s/one key-or-sql "Keyword") key-or-sql]]
  "row(expr, ...) sql function"
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

;; COMPOSITE FUNCTION HELPERS

(pls/defn-validated convert-to-iso8601-utc :- SqlRaw
  "Wraps to_char() and timezone() to return a UTC fixed ISO8601 format. This is
  required for PostgreSQL 9.3 especially when relying on the basic row_to_json
  response which is _not_ by default ISO 8601 when extracting a timezone (fixed
  in 9.4)."
  [expr :- key-or-sql]
  (hcore/raw (str "to_char(timezone('UTC'," (hfmt/to-sql expr) "), 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")))

;; NEW OPERATORS

; Custom formatter for PostgreSQL's ~ operator
(defmethod hfmt/fn-handler "~" [_ a b & more]
  (if (seq more)
    (apply hfmt/expand-binary-ops "~" a b more)
    (str (hfmt/to-sql a) " ~ " (hfmt/to-sql b))))
