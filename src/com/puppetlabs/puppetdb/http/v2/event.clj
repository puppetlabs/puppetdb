;; ## Request Format
;; TODO: fill this in once it stabilizes

(ns com.puppetlabs.puppetdb.http.v2.event
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.report :as query]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Given an optional `query`, an optional `report-id` (the id of the report),
  and a database connection, return a Ring response with the query results.  The
  result format conforms to that documented above.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [query report-id db]
  ;; TODO: implement query
  (if query
    (throw (UnsupportedOperationException. "query is not yet implemented")))

  (try
    (with-transacted-connection db
      (-> (query/resource-event-query->sql query report-id)
          (query/query-resource-events)
          (pl-http/json-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
          (produce-body
            (params "query")
            (params "report-id")
            (:scf-db globals)))}))

(defn verify-params
  "Ring middleware that checks the parameters for an `events` request"
  [app]
  (fn [{:keys [params] :as req}]
    (if (or (params "query")
            (params "report-id"))
      (app req)
      (pl-http/error-response "must provide at least one of 'query', 'report-id'"))))

(def events-app
  "Ring app for querying events"
  (-> routes
    verify-accepts-json
    verify-params))
