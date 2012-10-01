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

(ns com.puppetlabs.puppetdb.http.v1.facts
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.facts :as f]
            [ring.util.response :as rr])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn retrieve-facts-for-node
  "Produce a response body for a request to lookup facts for `node`."
  [node db]
  (let [facts (with-transacted-connection db
                (f/facts-for-node node))]
    (if-not (seq facts)
      (pl-http/json-response {:error (str "Could not find facts for " node)} pl-http/status-not-found)
      (pl-http/json-response {:name node :facts facts}))))


(def routes
  "Ring app for querying facts"
  (app
    [node &]
    (fn [{:keys [globals]}]
      (retrieve-facts-for-node node (:scf-db globals)))))

(def facts-app
  (verify-accepts-json routes))
