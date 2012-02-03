;; ## REST Fact endpoints
;;
;; There is only one route available for querying facts, which is
;; '/facts/<node>'. This route responds with a JSON version of the following
;; map:
;;
;;     {:name "node"
;;      :facts {"fact" "value"
;;              "fact" "value"
;;              ...}}
;;
;; If no facts are known for the node, the response is a 404 with an error message.

(ns com.puppetlabs.cmdb.http.facts
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.cmdb.query.facts :as f]
            [ring.util.response :as rr]))

(defn produce-body
  "Produce a response body for a request to lookup facts for `node`."
  [node db]
  (let [facts (f/facts-for-node db node)]
    (if-not (seq facts)
      (-> {:error (str "Could not find facts for " node)}
          (utils/json-response)
          (rr/status 404))
      (-> (json/generate-string {:name node :facts facts})
          (rr/response)
          (rr/header "Content-Type" "application/json")
          (rr/status 200)))))

(defn facts-app
  "Ring app for querying facts"
  [{:keys [params headers globals] :as request}]
  (cond
   (not (params "node"))
   (-> (rr/response "missing node")
       (rr/status 400))

   (not (utils/acceptable-content-type
         "application/json"
         (headers "accept")))
   (-> (rr/response "must accept application/json")
       (rr/status 406))

   :else
   (produce-body (params "node") (:scf-db globals))))
