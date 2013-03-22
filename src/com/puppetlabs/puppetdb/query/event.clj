;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.event
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]
            [cheshire.core :as json])
  (:use [com.puppetlabs.jdbc :only [query-to-vec underscores->dashes valid-jdbc-query?]]
        [com.puppetlabs.puppetdb.query :only [compile-term compile-and]]
        [clojure.core.match :only [match]]
        [clj-time.coerce :only [to-timestamp]]))


(defn compile-resource-event-inequality
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    ["timestamp"]
    {:where (format "resource_events.timestamp %s ?" op)
     :params [(to-timestamp value)] }

    :else (throw (IllegalArgumentException.
                   (str op " operator does not support object " path " for resource events")))))

(defn compile-resource-event-equality
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (match [path]
    ["report"]
    {:where "resource_events.report = ?"
     :params [value] }

    :else (throw (IllegalArgumentException.
                   (str path " is not a queryable object for resource events")))))

(defn resource-event-ops
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-resource-event-equality
      (= op "and") (partial compile-and resource-event-ops)
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

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [results (query-to-vec (apply vector sql params))]
    (map
      #(-> (utils/mapkeys underscores->dashes %)
           (update-in [:old-value] json/parse-string)
           (update-in [:new-value] json/parse-string))
      results)))

