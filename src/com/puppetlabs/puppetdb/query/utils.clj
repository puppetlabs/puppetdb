;; ## SQL/query Utility library
;;
;; This namespace contains utility functions related to how PuppetDB builds,
;; represents, and executes SQL queries.
(ns com.puppetlabs.puppetdb.query.utils)

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
