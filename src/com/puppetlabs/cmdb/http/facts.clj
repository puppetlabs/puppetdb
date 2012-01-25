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
            [com.puppetlabs.cmdb.query.facts :as f])
  (:use [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]))

(def
  ^{:doc "Content type for the set of facts for an individual node"}
  fact-set-c-t "application/vnd.com.puppetlabs.cmdb.fact-set+json")

(defn have-facts-for-node?
  "Attempt to fetch the facts for the given node and annotate the graphdata
with the facts if found. Otherwise, respond with an appropriate error message."
  [handler {:keys [params] :as request} graphdata]
  (let [node (params "node")
        db (get-in request [:globals :scf-db])
        facts (f/facts-for-node db node)]
    (if (seq facts)
      (annotated-return true {:annotate {:facts facts}})
      (utils/return-json-error false (str "Could not find facts for " node)))))

(defn fact-set-to-json
  "Respond with the facts for the requested node (supplied in the graphdata),
formatted as a JSON hash."
  [{:keys [params] :as request} {:keys [facts] :as graphdata}]
  {:pre [(seq facts)
         (params "node")]}
  (let [node (params "node")]
    (json/generate-string {:name node :facts facts})))

(defhandler fact-set-handler
            :allowed-methods        (constantly #{:get})
            :resource-exists?       have-facts-for-node?
            :content-types-provided (constantly {fact-set-c-t fact-set-to-json}))
