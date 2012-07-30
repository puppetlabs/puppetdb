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

