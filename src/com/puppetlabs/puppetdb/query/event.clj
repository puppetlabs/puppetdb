;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.event
  (:require [com.puppetlabs.utils :as utils])
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.puppetdb.query.utils :only [valid-query-format? sql-to-wire]]))


(defn event-group-query->sql
  "Given the set of inputs for an event group query, build up the corresponding
  SQL expression."
  [query group-id]
  ;; TODO: real precondition for the query argument.  Not supported yet.
  {:pre [(nil? query)]
   :post [(valid-query-format? %)]}
  (if group-id
    (vector " WHERE event_groups.group_id = ?" group-id)
    (vector "")))

(defn query-event-groups
  "Take a query and its parameters, and return a vector of matching event groups."
  [[sql & params]]
  {:pre [(string? sql)]}
  ;; TODO: do we need LIMIT stuff here, like we're doing with resource queries?
  (let [query   (format (str "SELECT group_id,
                                      start_time,
                                      end_time,
                                      receive_time
                                  FROM event_groups %s ORDER BY start_time DESC")
    sql)
        results   (map sql-to-wire (query-to-vec (apply vector query params)))]
    results))



(defn resource-event-query->sql
  "Given the set of inputs for a resource event query, build up the corresponding
  SQL expression."
  [query event-group-id]
  ;; TODO: real precondition for the query argument.  Not supported yet.
  {:pre [(nil? query)
         ((some-fn nil? string?) event-group-id)]
   :post [(valid-query-format? %)]}
  (if event-group-id
    (vector " WHERE resource_events.event_group_id = ?" event-group-id)
    (vector "")))

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  [[sql & params]]
  {:pre [(string? sql)]}
  ;; TODO: do we need LIMIT stuff here, like we're doing with resource queries?
  (let [query   (format (str "SELECT event_group_id,
                                      certname,
                                      status,
                                      timestamp,
                                      property_name,
                                      property_value,
                                      previous_value,
                                      resource_type,
                                      resource_title,
                                      message
                                  FROM resource_events %s")
                        sql)
        results   (map sql-to-wire (query-to-vec (apply vector query params)))]
    results))
