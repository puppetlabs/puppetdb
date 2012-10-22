;; ## Request Format
;; TODO: fill this in once it stabilizes

(ns com.puppetlabs.puppetdb.http.v2.event-group
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event :as query]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(defn produce-body
  "Given an optional `query`, an optional `group-id` (the id of the event group),
  and a database connection, return a Ring response with the query results.  The
  result format conforms to that documented above.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [query group-id db]
  ;; TODO: implement query
  (if query
    (throw (UnsupportedOperationException. "query is not yet implemented")))
  (try
    (with-transacted-connection db
      (-> (query/event-group-query->sql query group-id)
          (query/query-event-groups)
          (pl-http/json-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))



(defn event-groups-app
  "Ring app for querying event groups"
  [{:keys [params headers globals] :as request}]
  (cond
    ;; TODO: decide what params are required (if any)
;    (not (or (params "query")
;           (params "group-id")))
;    (pl-http/error-response "must provide at least one of 'query', 'group-id'")

    ;; TODO: this is copied and pasted from resources-app, should be able to
    ;;  be refactored
    (not (pl-http/acceptable-content-type
           "application/json"
           (headers "accept")))
    (-> (rr/response "must accept application/json")
      (rr/status pl-http/status-not-acceptable))

    :else
    (produce-body (params "query") (params "group-id") (:scf-db globals))))
