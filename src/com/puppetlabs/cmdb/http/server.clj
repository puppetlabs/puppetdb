;; ## REST server
;;
;; Consolidates our disparate REST endpoints into a single Ring
;; application.

(ns com.puppetlabs.cmdb.http.server
  (:use [com.puppetlabs.cmdb.http.command :only (command-app)]
        [com.puppetlabs.cmdb.http.facts :only (facts-app)]
        [com.puppetlabs.cmdb.http.metrics :only (metrics-app)]
        [com.puppetlabs.cmdb.http.resources :only (resources-app)]
        [com.puppetlabs.cmdb.http.node :only (node-app)]
        [com.puppetlabs.cmdb.http.catalog :only (catalog-app)]
        [com.puppetlabs.middleware :only (wrap-with-globals wrap-with-metrics)]
        [com.puppetlabs.utils :only (uri-segments)]
        [net.cgrand.moustache :only (app)]
        [ring.middleware.resource :only (wrap-resource)]
        [ring.middleware.params :only (wrap-params)]))

(def routes
  (app
   ["facts" node]
   {:get (fn [req]
           (facts-app (assoc-in req [:params "node"] node)))}

   ["nodes"]
   {:get node-app}

   ["catalog" node]
   {:get (fn [req]
           (catalog-app (assoc-in req [:params "node"] node)))}

   ["resources"]
   {:get resources-app}

   ["commands"]
   {:post command-app}

   ["metrics" &]
   {:get metrics-app}))

(defn build-app
  "Given an attribute map representing connectivity to the SCF
  database, generate a Ring application that handles queries"
  [globals]
  (-> routes
      (wrap-resource "public")
      (wrap-params)
      (wrap-with-metrics (atom {}) #(first (uri-segments %)))
      (wrap-with-globals globals)))
