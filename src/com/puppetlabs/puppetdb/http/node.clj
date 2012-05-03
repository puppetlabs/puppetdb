;; ## Request Format
;;
;; There is only one available routes, `/nodes?query=<query>`. The `query`
;; parameter is an optional JSON array of query predicates in prefix form.
;;
;; ### Terms
;;
;; The only accepted query terms are of the form `["fact" <fact name>]` or
;; `["node" "active"]`.
;;
;; ### Predicates
;;
;; The recognized predicates are `[= > < >= <= and or not]`.
;;
;; `[and or not]` each accept as arguments an array of predicates, which are
;; composed according to the operator.
;;
;; `[> < >= <=]` are arithmetic operators only, and will ignore values which
;; are not numeric.
;;
;; `=` is exact comparison only, and will not consider, for example, "0" and
;; "0.0" to be equal.
;;
;; ## Response Format
;;
;; The response is a JSON array of node names matching the predicates, sorted
;; in ascending order:
;;
;; `["foo.example.com" "bar.example.com" "baz.example.com"]`
;;
(ns com.puppetlabs.puppetdb.http.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.puppetdb.query.node :as node]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on
  `query`. If no `query` is supplied, all nodes will be returned."
  [query db]
  (try
    (with-transacted-connection db
      (let [query (if query (json/parse-string query true))
            sql   (node/query->sql query)
            nodes (node/search sql)]
        (utils/json-response nodes)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (utils/error-response e))
    (catch IllegalArgumentException e
      (utils/error-response e))))

;; TODO: Add an API to specify whether to include facts
(defn node-app
  "Ring app for querying nodes."
  [{:keys [params headers globals] :as request}]
  (cond
   (not (utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))
   :else
   (search-nodes (params "query") (:scf-db globals))))
