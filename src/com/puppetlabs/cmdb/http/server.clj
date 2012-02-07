;; REST server
;;
;; Consolidates our disparate REST endpoints into a single Ring
;; application.

(ns com.puppetlabs.cmdb.http.server
  (:use [com.puppetlabs.cmdb.http.command :only (command-app)]
        [com.puppetlabs.cmdb.http.facts :only (facts-app)]
        [com.puppetlabs.cmdb.http.resources :only (resources-app)]
        [net.cgrand.moustache :only (app)]
        [ring.middleware.params :only (wrap-params)]))

(def routes
  (app
   ["facts" node]
   {:get (fn [req]
           (facts-app (assoc-in req [:params "node"] node)))}

   ["resources"]
   {:get resources-app}

   ["commands"]
   {:post command-app}))

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
  (-> routes
      (wrap-params)
      (wrap-with-globals globals)))
