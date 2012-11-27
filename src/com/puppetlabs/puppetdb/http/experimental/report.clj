;; ## Request Format
;;
;; The single available route is '/reports?query=<query>'.
;; The `query` parameter is a JSON array of query predicates in
;  prefix form.
;;
;; The most common use for this query will probably be as a means of building up
;; a UI for "most recent reports".  The report ids can then be used as input for
;; the `events` query endpoint.
;;
;; ### Predicates
;;
;; #### =
;;
;; Reports for node with certname `foo.local`:
;;
;;    ["=", "certname", "foo.local"]
;;
;;
;; ## Response Format
;;
;; The response is a JSON array of report summaries for all reports
;; that matched the input parameters.  The summaries are sorted by
;; the completion time of the report, in descending order:
;;
;;`[
;;  {
;;    "end-time": "2012-10-29T18:38:01.000Z",
;;    "puppet-version": "3.0.1",
;;    "receive-time": "2012-10-29T18:38:04.238Z",
;;    "configuration-version": "1351535883",
;;    "start-time": "2012-10-29T18:38:00.000Z",
;;    "id": "d4bcb35a-fb7b-45da-84e0-fceb7a1df713",
;;    "certname": "foo.local",
;;    "report-format": 3
;;    },
;;  {
;;    "end-time": "2012-10-26T22:39:32.000Z",
;;    "puppet-version": "3.0.1",
;;    "receive-time": "2012-10-26T22:39:35.305Z",
;;    "configuration-version": "1351291174",
;;    "start-time": "2012-10-26T22:39:31.000Z",
;;    "id": "5ec13ff5-c6fd-43fb-b5b1-59a00ec8e1f1",
;;    "certname": "foo.local",
;;    "report-format": 3
;;    }
;;]`

(ns com.puppetlabs.puppetdb.http.experimental.report
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.report :as query]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(defn produce-body
  "Given an optional `query` and a database connection, return a Ring response
  with the query results.  The result format conforms to that documented above.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [query db]
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          (query/report-query->sql)
          (query/query-reports)
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
              (:scf-db globals)))}))

(def reports-app
  "Ring app for querying reports"
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")))
