;; ## REST Fact endpoints
;;
;; There is only one route available for querying facts, which is
;; '/facts/<node>'. This route responds with the following JSON:
;;
;;     {"name": "node"
;;      "facts":
;;        {"fact": "value",
;;         "fact": "value",
;;         ...}}
;;
;; If no facts are known for the node, the response is a 404 with an error message.

(ns com.puppetlabs.puppetdb.http.facts
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.facts :as f]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Produce a response body for a request to lookup facts for `node`."
  [node db]
  (let [facts (with-transacted-connection db
                (f/facts-for-node node))]
    (if-not (seq facts)
      (pl-http/json-response {:error (str "Could not find facts for " node)} pl-http/status-not-found)
      (pl-http/json-response {:name node :facts facts}))))

(defn facts-app
  "Ring app for querying facts"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "node"))
   (rr/status (rr/response "missing node")
              pl-http/status-bad-request)

   (not (pl-http/acceptable-content-type
         "application/json"
         (headers "accept")))
   (rr/status (rr/response "must accept application/json")
              pl-http/status-not-acceptable)

   :else
   (produce-body (params "node") (:scf-db globals))))
