(ns com.puppetlabs.cmdb.query
  (:require [clothesline.core :as cl]
            [com.puppetlabs.cmdb.query.resource :as resource]))

(def routes
  {"/resources" resource/resource-list-handler})

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
  [scf-db]
  (-> (cl/produce-handler routes)
      (wrap-with-globals {:scf-db scf-db})))

