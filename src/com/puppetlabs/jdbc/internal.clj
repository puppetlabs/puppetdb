;; ## internal (private-ish) helper functions for com.puppetlabs.jdbc.  External
;;  code should not call any of these functions directly, as they are subject
;;  to change without notice.

(ns com.puppetlabs.jdbc.internal
  (:import (com.jolbox.bonecp.hooks AbstractConnectionHook))
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]))

(defn query-params->str*
  "Helper method for converting a list of parameters from a prepared statement
  to a human-readable string.  Our current connection pool library does not
  make these available unless we've enabled SQL statement logging, so we have
  to check first to see whether that is enabled.  If it's not, we print out
  a message letting the user know how they can enable it."
  [log-statements params]
  { :pre [(instance? java.lang.Boolean log-statements)]}
  (if log-statements
    (format "Query Params: %s"
      (clojure.string/join ", "
        (map #(str "'" (.getValue %) "'") params)))
    (str "(Query params unavailable: to enable logging of query params, please set "
      "'log-statements' to true in the [database] section of your config file.)")))

(defn connection-hook*
  "Helper method for building up a `ConnectionHook` for our connection pool.
  Currently only defines behavior for "
  [log-statements query-execution-limit]
  { :pre [(instance? java.lang.Boolean log-statements)
          (and (integer? query-execution-limit)
            (pos? query-execution-limit))]}
  (proxy [AbstractConnectionHook] []
    ;; the name of this method is a bit misleading; the way it actually works
    ;; is that *after* a query completes, the connection class will check to
    ;; see how long it took... and if it took longer than the specified limit,
    ;; it will call this method after the fact.
    ;;
    ;; TODO: consider adding a call to EXPLAIN here.
    (onQueryExecuteTimeLimitExceeded
      [conn stmt sql params time-elapsed]
      (log/warn (format (str "Query exceeded specified time limit of %s seconds.  "
                          "Actual execution time: %.4f seconds; Query: %s; "
                          (query-params->str* log-statements params))
                  query-execution-limit
                  (/ time-elapsed 1000000000.0)
                  stmt)))))


(defn add-limit-clause*
  "Helper function for ensuring that a query does not return more than a certain
  number of results.  (Adds a limit clause to an SQL query if necessary.)

  Accepts two parameters: `limit` and `query-vec`.  `query-vec` should be a vector
  of Strings; the first String is the SQL query with optional placeholders, and
  the remaining items are the parameters to the query.

  `limit` is an integer specifying the maximum number of results that we are looking
  for.  If `limit` is zero, then we return the original `query-vec` unaltered.  If
  `limit is greater than zero, we add a limit clause using the time-honored trick
  of using the value of `limit + 1`;  This allows us to later compare the size of
  the result set against the original limit and detect cases where we've exceeded
  the maximum."
  [limit query-vec]
  {:pre [(and (integer? limit) (>= limit 0))
         (vector? query-vec)]}
  (if (> limit 0)
    (apply vector (str (first query-vec) " LIMIT " (inc limit)) (rest query-vec))
    query-vec))

(defn throw-limit-exception!*
  "Helper method; simply throws an exception with a message explaining
  that a query result limit was exceeded."
  [limit]
  ; TODO: tempted to create a custom exception for this, or at least
  ;  some kind of general-purpose PuppetDBException
  (throw (IllegalArgumentException.
           (format
             "Query returns more than the maximum number of results (max: %s)"
             limit))))


(defn limit-result-set!*
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
    ;;  correctly identify whether the query *exceeded* the specified limit.
    (let [limited-result-set (take (inc limit) result-set)]
      (when (> (count limited-result-set) limit)
        (throw-limit-exception!* limit))
      (take limit limited-result-set))
    result-set))

