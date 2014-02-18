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
            [com.puppetlabs.puppetdb.query.resources :as r]
            [com.puppetlabs.cheshire :as json]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(def version :v1)

(defn munge-result-rows
  "Munge the result rows so that they will be compatible with the v1 API specification"
  [rows]
  (map (fn [r] (-> r
                 (assoc :sourceline (:line r))
                 (assoc :sourcefile (:file r))
                 (dissoc :file :line)))
    rows))

(defn produce-body
  "Given a query, and database connection, return a Ring
  response with the query results. The result format conforms to that documented
  above.

  If the query can't be parsed, a 400 is returned.

  If the query would return more than `limit` results, `status-internal-error` is returned."
  [query db]
  (try
    (with-transacted-connection db
      (-> (r/query->sql version (json/parse-string query true))
          (:result)
          (munge-result-rows)
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
              (:scf-read-db globals)))}))

(def resources-app
  "Ring app for querying resources"
  (-> routes
    verify-accepts-json
    (validate-query-params {:required ["query"]})))
