;; ## SQL/query Utility library
;;
;; This namespace contains utility functions related to how PuppetDB builds,
;; represents, and executes SQL queries.
(ns com.puppetlabs.puppetdb.query.utils
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]))

(defn valid-query-format?
  "Most SQL queries generated in the PuppetDB code base are represented internally
  as a vector whose first item is the SQL string (with optional '?' placeholders),
  and whose remaining items (if any) are simple data types that can be passed
  to a JDBC prepared statement as parameter values to bind to the placeholders
  in the SQL string.  This function validates that a form complies to this structure.
  It is intended primarily for use in pre- and post-conditions, for validation."
  [q]
  (and
    (vector? q)
    (string? (first q))
    (every? (complement coll?) (rest q))))

(defn sql-to-wire
  "Given an object returned from a JDBC query, convert it to a data structure
  compatible with the PuppetDB wire format.  For the most part, this entails
  replacing underscores in field names with dashes.

  You may also pass in an optional map (`keys-fns`), which maps lists of keys to
  a function that should be applied to them.  This is most useful for data type
  conversion; so, e.g., if you have some values coming out from the JDBC driver
  that are numeric or date/time objects, and the wire format calls for them to
  be represented as strings, you could map those keys to the `to-string` function."
  ([obj]
    (sql-to-wire obj {}))
  ([obj keys-fns]
    {:pre [(map? keys-fns)
           (map? obj)]}
    (let [updated-obj (utils/maptrans keys-fns obj)]
      (utils/mapkeys #(keyword (string/replace (name %) \_ \-)) updated-obj))))


(defn wire-to-sql
  "Given a data structure compatible with the PuppetDB wire format, convert it
  to a form compatible with the JDBC query functions.  For the most part, this
  entails replacing dashes in field names with underscores.

  You may also pass in an optional map (`key-fns`), which maps lists of keys to
  a function that should be applied to them.  This is most useful for data type
  conversion; so, e.g., if you have some values that are represented as Strings
  in the wire format, but map to date/time columns in the database, you could
  map those keys to the `to-timestamp` function."
  ([obj]
    (wire-to-sql obj {}))
  ([wire-obj keys-fns]
    {:pre [(map? keys-fns)
           (map? wire-obj)]}
    (let [updated-wire-obj (utils/maptrans keys-fns wire-obj)]
      (utils/mapkeys #(keyword (string/replace (name %) \- \_)) updated-wire-obj))))
