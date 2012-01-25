;; REST server
;;
;; Consolidates our disparate REST endpoints into a single Ring
;; application.

(ns com.puppetlabs.cmdb.http.server
  (:require [clothesline.core :as cl]
            [com.puppetlabs.cmdb.http.command :as command]
            [com.puppetlabs.cmdb.http.facts :as facts]
            [com.puppetlabs.cmdb.http.resources :as resources]))

(def routes
  {"/resources"   resources/resource-list-handler
   "/facts/:node" facts/fact-set-handler
   "/commands"    command/http->mq-handler})

(defn wrap-with-globals
  "Ring middleware that will add to each request a :globals attribute:
  a map containing various global settings"
  [app globals]
  (fn [req]
    (let [new-req (assoc req :globals globals)]
      (app new-req))))

(defn build-app
  "Given an attribute map representing connectivity to the SCF
  database, generate a Ring application that handles queries"
  [globals]
  (-> (cl/produce-handler routes)
      (wrap-with-globals globals)))
