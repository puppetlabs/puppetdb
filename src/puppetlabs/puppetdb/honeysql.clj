(ns puppetlabs.puppetdb.honeysql
  "Some generic HoneySQL extensions, candidates for re-usability and
  potential upstream submission.")

;; SCHEMA

;; COMMMON DIRECT SQL FUNCTIONS

(defn coalesce
  "coalesce(arg, ...) sql function"
  [& args]
  (into [:coalesce] args))

(defn scast
  "cast(source AS target) sql function"
  [source
   target]
  [:cast source target])

(defn json-agg
  "json_agg(expr) sql function"
  [expr]
  [:json_agg expr])

(defn row-to-json
  "row_to_json(record) sql function"
  [record]
  [[:row_to_json record]])

;; Misc honeysql functions

(defn extract-sql
  "Returns a keyworidzed version of the SQL string if `k` is an sql raw, otherwise returns `s`"
  [s]
  (if (keyword? s)
    s
    (keyword (second s))))
