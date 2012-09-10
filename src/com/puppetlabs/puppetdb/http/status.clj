;; ## Status query
;;
;; This implements the status query HTTP API according to the [status query
;; spec](../spec/status.md).
(ns com.puppetlabs.puppetdb.http.status
  (:require [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [com.puppetlabs.puppetdb.query.status]
        [net.cgrand.moustache :only (app)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-node-status
  "Produce a response body for a request to retrieve the status of `node`."
  [node db]
  (if-let [status (with-transacted-connection db
                    (node-status node))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " node)} pl-http/status-not-found)))

(defn node-status-app
  "Ring app for retrieving node status"
  [{:keys [params headers globals] :as request}]
  (let [node (params "node")]
    (cond
     (empty? node)
     (rr/status (rr/response "missing node")
                pl-http/status-bad-request)

     (not (pl-http/acceptable-content-type
           "application/json"
           (headers "accept")))
     (rr/response "must accept application/json")

     :else
     (produce-node-status node (:scf-db globals)))))

(def status-app
  (app
   ["nodes" node]
   {:get (fn [req]
           (node-status-app (assoc-in req [:params "node"] node)))}))
