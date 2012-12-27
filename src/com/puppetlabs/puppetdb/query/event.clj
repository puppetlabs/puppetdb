;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.event
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]
            [cheshire.core :as json])
  (:use [com.puppetlabs.jdbc :only [query-to-vec underscores->dashes valid-jdbc-query?]]))

;; ## Resource Event query functions
;;
;; The following functions provide the basic logic for composing and executing
;; queries against resource events (the individual events that make up a report).

(defmulti compile-resource-event-term
  "Recursively compile a resource event query into a structured map reflecting
  the terms of the query."
  (fn [query]
    (let [operator (string/lower-case (first query))]
      (cond
        (#{"="} operator) :equality
        ))))

(defn resource-event-query->sql
  "Compile a resource event query into an SQL expression."
  [query]
  {:pre [(vector? query)]
   :post [(valid-jdbc-query? %)]}
  (let [{:keys [where params]} (compile-resource-event-term query)]
    (apply vector (format " WHERE %s" where) params)))

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  [[sql & params]]
  {:pre [(string? sql)]}
  (let [query   (format (str "SELECT report,
                                      status,
                                      timestamp,
                                      resource_type,
                                      resource_title,
                                      property,
                                      new_value,
                                      old_value,
                                      message
                                  FROM resource_events %s")
                        sql)
        results   (map
                    #(-> (utils/mapkeys underscores->dashes %)
                         (update-in [:old-value] json/parse-string)
                         (update-in [:new-value] json/parse-string))
                    (query-to-vec (apply vector query params)))]
    results))

(defmethod compile-resource-event-term :equality
  [[op path value :as term]]
  {:post [(map? %)
          (string? (:where %))]}
  (let [count (count term)]
    (if (not= 3 count)
      (throw (IllegalArgumentException.
               (format "%s requires exactly two arguments, but we found %d" op (dec count))))))
  (case path
    "report" {:where "resource_events.report = ?"
              :params [value] }
    (throw (IllegalArgumentException.
                 (str term " is not a valid query term")))))
