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
            [cheshire.core :as json])
  (:use [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]))

(def
  ^{:doc "Content type for an individual resource"}
  resource-c-t "application/vnd.com.puppetlabs.cmdb.resource+json")

(def
  ^{:doc "Content type for a list of resources"}
  resource-list-c-t "application/vnd.com.puppetlabs.cmdb.resource-list+json")

(defn malformed-request?
  "Validate the JSON-encoded query for this resource, and annotate the
graphdata with the compiled data structure.  This ensures that only valid
input queries can make it through to the rest of the system."
  [_ {:keys [params] :as request} _]
  (try
    (let [db (get-in request [:globals :scf-db])
          sql (r/query->sql db (json/parse-string (get params "query" "null") true))]
      (annotated-return false {:annotate {:query sql}}))
    (catch Exception e
      (utils/return-json-error true (.getMessage e)))))

(defn resource-list-as-json
  "Fetch a list of resources from the database, formatting them as a
JSON array, and returning them as the body of the response."
  [request graphdata]
  (let [db (get-in request [:globals :scf-db])]
    (json/generate-string (or (vec (r/query-resources db (:query graphdata)))
                              []))))

(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))
