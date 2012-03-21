(ns com.puppetlabs.cmdb.http.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.cmdb.query.node :as node]
            [ring.util.response :as rr]))

(defn search-nodes
  "Produce a response body for a request to search for nodes based on
  `query`. If no `query` is supplied, all nodes will be returned."
  [query db]
  (try
    (let [query (if query (json/parse-string query true))
          sql   (node/query->sql db query)
          nodes (node/search db sql)]
      (utils/json-response nodes))
    (catch org.codehaus.jackson.JsonParseException e
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
