(ns com.puppetlabs.cmdb.query.facts
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojureql.core
        [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]
        [com.puppetlabs.jdbc :only [query-to-vec]]))

(def
  ^{:doc "Content type for the set of facts for an individual node"}
  fact-set-c-t "application/vnd.com.puppetlabs.cmdb.fact-set+json")

(defn facts-for-node
  [db name]
  (let [facts (-> (table db :certname_facts)
                  (project [:fact, :value])
                  (select (where (= :certname name))))]
    (into {} (for [fact @facts]
               [(:fact fact) (:value fact)]))))

(defn have-facts-for-node?
  [handler {:keys [params] :as request} graphdata]
  (let [name (params "node")
        db (get-in request [:globals :scf-db])
        facts (facts-for-node db name)]
    (if (seq facts)
      (annotated-return true {:annotate {:facts facts}})
      (annotated-return false {:headers {"Content-Type" "application/json"}
                               :annotate {:body (json/generate-string
                                                  {:error (str "Could not find facts for " name)})}}))))

(defn fact-set-to-json
  [{:keys [params] :as request} {:keys [facts] :as graphdata}]
  {:pre [(seq facts)
         (params "node")]}
  (let [name (params "node")]
    (json/generate-string {:name name :facts facts})))

(defhandler fact-set-handler
            :allowed-methods        (constantly #{:get})
            :resource-exists?       have-facts-for-node?
            :content-types-provided (constantly {fact-set-c-t fact-set-to-json}))
