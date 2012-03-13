(ns com.puppetlabs.cmdb.http.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.cmdb.query.node :as node]
            [ring.util.response :as rr]))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on `filter-expr`."
  [filter-expr db]
  (try
    (let [query (node/query->sql db filter-expr)
          nodes (node/search db query)]
      (utils/json-response nodes))
    (catch org.codehaus.jackson.JsonParseException e
      (-> (.getMessage e)
        (rr/response)
        (rr/status 400)))
    (catch IllegalArgumentException e
      (-> (.getMessage e)
        (rr/response)
        (rr/status 400)))))

;; TODO: Add an API to specify whether to include facts
(defn node-app
  "Ring app for querying nodes"
  [{:keys [params headers globals] :as request}]
  (cond
    (not (utils/acceptable-content-type
           "application/json"
           (headers "accept")))
    (-> (rr/response "must accept application/json")
      (rr/status 406))
    :else
    (search-nodes (json/parse-string (params "query") true) (:scf-db globals))))
