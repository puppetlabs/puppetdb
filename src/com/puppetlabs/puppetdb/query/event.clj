;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.event
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]
            [cheshire.core :as json])
  (:use [com.puppetlabs.jdbc :only [limited-query-to-vec
                                    underscores->dashes
                                    dashes->underscores
                                    valid-jdbc-query?
                                    add-limit-clause]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize]]
        [com.puppetlabs.puppetdb.query :only [compile-term compile-and compile-or]]
        [clojure.core.match :only [match]]
        [clj-time.coerce :only [to-timestamp]]))

(defn compile-resource-event-inequality
  "Compile a timestamp inequality for a resource event query (> < >= <=).
  The `value` for comparison must be coercible to a timestamp via
  `clj-time.coerce/to-timestamp` (e.g., an ISO-8601 compatible date-time string)."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    ["timestamp"]
    (if-let [timestamp (to-timestamp value)]
      {:where (format "resource_events.timestamp %s ?" op)
       :params [(to-timestamp value)]}
      (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))

    :else (throw (IllegalArgumentException.
                   (str op " operator does not support object '" path "' for resource events")))))

(defn compile-resource-event-equality
  "Compile an = predicate for resource event fact query. `path` represents the field to
  query against, and `value` is the value."
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (match [path]
    [(field :when #{"report" "resource-type" "resource-title" "status" "property" "message"})]
    {:where (format "resource_events.%s = ?" (dashes->underscores field))
     :params [value] }

    [(field :when #{"old-value" "new-value"})]
    {:where (format "resource_events.%s = ?" (dashes->underscores field))
     :params [(db-serialize value)] }

    :else (throw (IllegalArgumentException.
                   (str path " is not a queryable object for resource events")))))

(defn resource-event-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-resource-event-equality
      (= op "and") (partial compile-and resource-event-ops)
      (= op "or") (partial compile-or resource-event-ops)
      (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op))))

(defn query->sql
  "Compile a resource event `query` into an SQL expression."
  [query]
  {:pre  [(vector? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-term resource-event-ops query)
        sql (format (str "SELECT report,
                                      status,
                                      timestamp,
                                      resource_type,
                                      resource_title,
                                      property,
                                      new_value,
                                      old_value,
                                      message
                                  FROM resource_events WHERE %s")
              where)]
    (apply vector sql params)))

(defn limited-query-resource-events
  "Take a limit, a query, and its parameters, and return a vector of resource
   events which match.  Throws an exception if the query would
   return more than `limit` results.  (A value of `0` for `limit` means
   that the query should not be limited.)"
  [limit [query & params]]
  {:pre  [(and (integer? limit) (>= limit 0))]
   :post [(or (zero? limit) (<= (count %) limit))]}
  (let [limited-query (add-limit-clause limit query)
        results       (limited-query-to-vec limit (apply vector limited-query params))]
    (map
      #(-> (utils/mapkeys underscores->dashes %)
         (update-in [:old-value] json/parse-string)
         (update-in [:new-value] json/parse-string))
      results)))

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  [[sql & params]]
  {:pre [(string? sql)]}
  (limited-query-resource-events 0 (apply vector sql params)))

