;; ## Request Format
;;
;; The single available route is `/resources?query=<query>`. The `query`
;; parameter is a JSON array of query predicates in prefix form.
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
;;     ["=" ["node" "<this value is ignored>"] "foo.example.com"]
;;
;; Resources whose owner parameter is "joe":
;;
;;     ["=" ["parameter" "owner"] "joe"]
;;
;; Resources whose title is "/etc/hosts"; "title" may be replaced with any legal column of the `resources` table, to query against that column:
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
;; The response is a list of resource objects, returned in JSON form. Each
;; resource object is a map of the following form:
;;
;;     {:hash       "the resource's unique hash"
;;      :type       "File"
;;      :title      "/etc/hosts"
;;      :exported   "true"
;;      :sourcefile "/etc/puppet/manifests/site.pp"
;;      :sourceline "1"
;;      :parameters {<parameter> <value>
;;                   <parameter> <value>
;;                   ...}}

(ns com.puppetlabs.cmdb.http.resources
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.cmdb.query.resource :as r]
            [cheshire.core :as json]
            [ring.util.response :as rr]))

(defn produce-body
  "Given a query and database connection, return a Ring response with
  the query results. The result format conforms to that documented
  above.

  If the query can't be parsed, a 400 is returned."
  [query db]
  (try
    (let [q (r/query->sql db (json/parse-string query true))]
      (-> (r/query-resources db q)
          (vec)
          (utils/json-response)
          (rr/status 200)))
    (catch org.codehaus.jackson.JsonParseException e
      (-> (.getMessage e)
          (rr/response)
          (rr/status 400)))
    (catch IllegalArgumentException e
      (-> (.getMessage e)
          (rr/response)
          (rr/status 400)))))

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
