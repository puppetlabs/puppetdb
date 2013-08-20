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
        [com.puppetlabs.puppetdb.http.v2.resources :only [produce-body]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (produce-body (params "query") r/v1-query->sql (:scf-db globals)))}))

(def resources-app
  "Ring app for querying resources"
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")))
