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
;; Resources tagged with "foo" (irrespective of other tags):
;;
;;     ["=" "tag" "foo"]
;;
;; Resources for the node "foo.example.com":
;;
;;     ["=" ["node" "name"] "foo.example.com"]
;;     ["=" ["node" "active"] true]
;;
;; Resources whose owner parameter is "joe":
;;
;;     ["=" ["parameter" "owner"] "joe"]
;;
;; Resources whose title is "/etc/hosts"; "title" may be replaced with
;; any legal column of the `resources` table, to query against that
;; column:
;;
;;     ["=" "title" "/etc/hosts"]
;;
;; #### and
;;
;; Resources whose owner is "joe" and group is "people":
;;
;;     ["and" ["=" ["parameter" "owner"] "joe"]
;;            ["=" ["parameter" "group"] "people"]]
;;
;; #### or
;;
;; Resources whose owner is "joe" or "jim":
;;
;;     ["or" ["=" ["parameter" "owner"] "joe"]
;;           ["=" ["parameter" "owner"] "jim"]]
;;
;; #### not
;;
;; Resources whose owner is not "joe" AND is not "jim":
;;
;;     ["not" ["=" ["parameter" "owner"] "joe"]
;;            ["=" ["parameter" "owner"] "jim"]]
;;
;; ## Response Format
;;
;; The response is a list of resource objects, returned in JSON
;; form. Each resource object is a map of the following form:
;;
;;     {:certname   "the certname of the associated host"
;;      :resource   "the resource's unique hash"
;;      :type       "File"
;;      :title      "/etc/hosts"
;;      :exported   "true"
;;      :tags       ["foo" "bar"]
;;      :sourcefile "/etc/puppet/manifests/site.pp"
;;      :sourceline "1"
;;      :parameters {<parameter> <value>
;;                   <parameter> <value>
;;                   ...}}

(ns com.puppetlabs.cmdb.http.resources
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.cmdb.query.resource :as r]
            [cheshire.core :as json]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Given a query and database connection, return a Ring response with
  the query results. The result format conforms to that documented
  above.

  If the query can't be parsed, a 400 is returned."
  [query db]
  (try
    (let [q (r/query->sql db (json/parse-string query true))]
      (-> (with-transacted-connection db
            (r/query-resources q))
          (utils/json-response)))
    (catch org.codehaus.jackson.JsonParseException e
      (utils/error-response e))
    (catch IllegalArgumentException e
      (utils/error-response e))))

(defn resources-app
  "Ring app for querying resources"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))
   :else
   (produce-body (params "query" "null") (:scf-db globals))))
