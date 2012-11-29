;; ## Status query
;;
;; This implements the status query HTTP API according to the [status query
;; spec](../spec/status.md).
(ns com.puppetlabs.puppetdb.http.v2.status
  (:require [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use com.puppetlabs.puppetdb.query.status
        com.puppetlabs.middleware
        [net.cgrand.moustache :only (app)]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-node-status
  "Produce a response body for a request to retrieve the status of `node`."
  [node db]
  (if-let [status (with-transacted-connection db
    (node-status node))]
    (pl-http/json-response status)
    (pl-http/json-response {:error (str "No information is known about " node)} pl-http/status-not-found)))

(def routes
  (app
    [node]
    {:get (fn [{:keys [globals]}]
            (produce-node-status node (:scf-db globals)))}))

(def status-app
  "Moustache app for retrieving status information"
  (verify-accepts-json routes))
