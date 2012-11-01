;; ## Request Format
;;
;; The single available route is `/resources?query=<query>`. The
;; `query` parameter is a JSON array of query predicates in prefix
;; form.
;;
;; ### Predicates
;;
;; #### =
;;
;; Resources tagged with "foo" (case-insensitive, and irrespective of other tags):
;;
;;     ["=", "tag", "foo"]
;;
;; Resources for the node "foo.example.com":
;;
;;     ["=", ["node", "name"], "foo.example.com"]
;;     ["=", ["node", "active"], true]
;;
;; Resources whose owner parameter is "joe":
;;
;;     ["=", ["parameter", "owner"], "joe"]
;;
;; Resources whose title is "/etc/hosts"; "title" may be replaced with
;; any legal column of the `resources` table, to query against that
;; column:
;;
;;     ["=", "title", "/etc/hosts"]
;;
;; #### and
;;
;; Resources whose owner is "joe" and group is "people":
;;
;;     ["and", ["=", ["parameter", "owner"], "joe"]
;;             ["=", ["parameter", "group"], "people"]]
;;
;; #### or
;;
;; Resources whose owner is "joe" or "jim":
;;
;;     ["or", ["=", ["parameter", "owner"], "joe"]
;;            ["=", ["parameter", "owner"], "jim"]]
;;
;; #### not
;;
;; Resources whose owner is not "joe" AND is not "jim":
;;
;;     ["not", ["=", ["parameter", "owner"], "joe"]
;;             ["=", ["parameter", "owner"], "jim"]]
;;
;; ## Response Format
;;
;; The response is a list of resource objects, returned in JSON
;; form. Each resource object is a map of the following form:
;;
;;     {"certname":   "the certname of the associated host",
;;      "resource":   "the resource's unique hash",
;;      "type":       "File",
;;      "title":      "/etc/hosts",
;;      "exported":   "true",
;;      "tags":       ["foo", "bar"],
;;      "sourcefile": "/etc/puppet/manifests/site.pp",
;;      "sourceline": "1",
;;      "parameters": {"parameter": "value",
;;                     "parameter": "value",
;;                     ...}}

(ns com.puppetlabs.puppetdb.http.v1.resources
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resource :as r]
            [cheshire.core :as json]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Given a `limit`, a query, and database connection, return a Ring
  response with the query results. The result format conforms to that documented
  above.

  If the query can't be parsed, a 400 is returned.

  If the query would return more than `limit` results, `status-internal-error` is returned."
  [limit query db]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          (r/v1-query->sql)
          ((partial r/limited-query-resources limit))
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
            (produce-body (:resource-query-limit globals) (params "query") (:scf-db globals)))}))

(def resources-app
  "Ring app for querying resources"
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")))
