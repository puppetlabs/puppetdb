(ns puppetlabs.pe-puppetdb-extensions.server
  (:require [compojure.core :as compojure]
            [puppetlabs.pe-puppetdb-extensions.catalogs :as catalogs]
            [puppetlabs.pe-puppetdb-extensions.state-overview :as state-overview]
            [puppetlabs.puppetdb.middleware :as mid]))

(defn v1-app
  [query-fn get-shared-globals]
  (compojure/routes
   (compojure/GET "/historical-catalogs" []
     catalogs/historical-catalogs-app)
   (compojure/GET "/resource-graphs" []
     catalogs/resource-graphs-app)
   (compojure/GET "/state-overview" []
     (state-overview/state-overview-app query-fn))))

(defn build-app
  [query-fn get-shared-globals]
  (-> (compojure/routes
       (compojure/context "/v1" [] (v1-app query-fn get-shared-globals)))
      (mid/wrap-with-globals get-shared-globals)
      mid/verify-accepts-json))

