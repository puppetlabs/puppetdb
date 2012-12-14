;; ## Request Format
;;
;; There is only one available routes, `/nodes?query=<query>`. The `query`
;; parameter is an optional JSON array of query predicates in prefix form.
;;
;; ### Terms
;;
;; The only accepted query terms are of the form `["fact", <fact name>]` or
;; `["node", "active"]`.
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
;; `["foo.example.com", "bar.example.com", "baz.example.com"]`
;;
(ns com.puppetlabs.puppetdb.http.v1.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.node :as node]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)] ))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on
  `query`. If no `query` is supplied, all nodes will be returned."
  [query db]
  (try
    (with-transacted-connection db
      (let [query (if query (json/parse-string query true))
            sql   (node/v1-query->sql query)
            nodes (mapv :name (node/query-nodes sql))]
        (pl-http/json-response nodes)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

;; TODO: Add an API to specify whether to include facts
(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (search-nodes (params "query") (:scf-db globals)))}))

(def node-app
  "Ring app for querying nodes."
  (verify-accepts-json routes))
