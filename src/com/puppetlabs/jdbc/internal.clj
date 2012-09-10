;; ## JDBC helper functions
;;
;; *External code should not call any of these functions directly, as they are*
;; *subject to change without notice.*

(ns com.puppetlabs.jdbc.internal
  (:use [clojure.string :only (join)])
  (:import (com.jolbox.bonecp.hooks AbstractConnectionHook))
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]))

(defn query-param->str
  "Helper method for converting a single parameter from a prepared statement
  to a human-eradable string, including the java type of the param value."
  [param]
  (let [val (.getValue param)]
    (format "(%s - %s)"
            (if (string? val) (str "'" val "'") val)
            (pr-str (type val)))))

(defn query-params->str
  "Helper method for converting a list of parameters from a prepared statement
  to a human-readable string.  Our current connection pool library does not
  make these available unless we've enabled SQL statement logging, so we have
  to check first to see whether that is enabled.  If it's not, we print out
  a message letting the user know how they can enable it."
  [log-statements? params]
  (if log-statements?
    (format "Query Params: %s"
            (join ", " (map query-param->str params)))
    (str "(Query params unavailable: to enable logging of query params, please set "
         "'log-statements' to true in the [database] section of your config file.)")))

(defn connection-hook
  "Helper method for building up a `ConnectionHook` for our connection pool.
  Currently only defines behavior for `onQueryExecuteTimeLimitExceeded`, which
  is called for slow queries.  There are several other hooks available that we
  could provide handlers for in the future."
  [log-statements? query-execution-limit]
  (proxy [AbstractConnectionHook] []
    ;; the name of this method is a bit misleading; the way it actually works
    ;; is that *after* a query completes, the connection class will check to
    ;; see how long it took... and if it took longer than the specified limit,
    ;; it will call this method after the fact.
    ;;
    ;; TODO: consider adding a call to EXPLAIN here.
    (onQueryExecuteTimeLimitExceeded
      [conn stmt sql params time-elapsed]
      (log/warn (format (str "Query slower than %ss threshold:  "
                             "actual execution time: %.4f seconds; Query: %s; "
                             (query-params->str log-statements? params))
                        query-execution-limit
                        (/ time-elapsed 1000000000.0)
                        sql)))))

(defn limit-exception
  "Helper method; simply throws an exception with a message explaining
  that a query result limit was exceeded."
  [limit]
  ;; TODO: tempted to create a custom exception for this, or at least
  ;; some kind of general-purpose PuppetDBException
  (IllegalStateException.
   (format
    "Query returns more than the maximum number of results (max: %s)"
    limit)))


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
