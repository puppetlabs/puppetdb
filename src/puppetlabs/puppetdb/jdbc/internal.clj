(ns puppetlabs.puppetdb.jdbc.internal
  "JDBC helper functions

   *External code should not call any of these functions directly, as they are*
   *subject to change without notice.*"
  (:require [puppetlabs.i18n.core :refer [tru]]))

(defn limit-exception
  "Helper method; simply throws an exception with a message explaining
  that a query result limit was exceeded."
  [limit]
  (IllegalStateException.
   (tru "Query returns more than the maximum number of results (max: {0})" limit)))


(defn limit-result-set!
  "Given a `limit` and a `result-set` (which is usually the result of a call to
  `clojure.java.jdbc/with-query-results`), this function verifies that the
  `result-set` does not contain more than `limit` results and then returns the
  results.

  If `limit` is zero, the original `result-set` is returned unmodified.

  Throws an exception if the `result-set` contains more than `limit` results."
  [limit result-set]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (if (pos? limit)
    ;; we're doing a `take` with `limit + 1` here, so that we can
    ;; correctly identify whether the query *exceeded* the specified
    ;; limit.
    (let [limited-result-set (take (inc limit) result-set)]
      (when (> (count limited-result-set) limit)
        (throw (limit-exception limit)))
      limited-result-set)
    result-set))
