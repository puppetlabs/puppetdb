;; ## Request Format
;;
;; The single available route is '/events?query=<query>'.
;; The `query` parameter is a JSON array of query predicates in
;  prefix form.
;;
;; This query can be used to retrieve all of the events for a report.
;;
;; ### Predicates
;;
;; #### =
;;
;; Events for report with report `38ff2aef3ffb7800fe85b322280ade2b867c8d27`:
;;
;;    ["=", "report", "38ff2aef3ffb7800fe85b322280ade2b867c8d27"]
;;
;;
;; ## Response Format
;;
;; The response is a JSON array of events that matched the input parameters.
;; The events are sorted by their timestamps, in descending order:
;;
;;`[
;;    {
;;      "old-value": "absent",
;;      "property": "ensure",
;;      "timestamp": "2012-10-30T19:01:05.000Z",
;;      "resource-type": "File",
;;      "resource-title": "/tmp/reportingfoo",
;;      "new-value": "file",
;;      "message": "defined content as '{md5}49f68a5c8493ec2c0bf489821c21fc3b'",
;;      "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
;;      "status": "success"
;;    },
;;    {
;;      "old-value": "absent",
;;      "property": "message",
;;      "timestamp": "2012-10-30T19:01:05.000Z",
;;      "resource-type": "Notify",
;;      "resource-title": "notify, yo",
;;      "new-value": "notify, yo",
;;      "message": "defined 'message' as 'notify, yo'",
;;      "report": "38ff2aef3ffb7800fe85b322280ade2b867c8d27",
;;      "status": "success"
;;    }
;;  ]`

(ns com.puppetlabs.puppetdb.http.experimental.event
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.puppetdb.query.event :as query]
            [cheshire.core :as json]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Given a `limit`, a query and a database connection, return a Ring response
  with the query results.  The result format conforms to that documented above.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned.

  If the query would return more than `limit` results, `status-internal-error`
  is returned."
  [limit query paging-options db]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          (query/query->sql)
          ((partial query/limited-query-resource-events limit paging-options))
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
    {:get (fn [{:keys [params globals paging-options]}]
            (let [limit (:event-query-limit globals)]
              (produce-body limit (params "query") paging-options (:scf-db globals))))}))

(def events-app
  "Ring app for querying events"
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")
    wrap-with-paging-options))
