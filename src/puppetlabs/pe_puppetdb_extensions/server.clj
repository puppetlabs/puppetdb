(ns puppetlabs.pe-puppetdb-extensions.server
  (:require [puppetlabs.pe-puppetdb-extensions.state-overview :as state-overview]
            [compojure.core :as compojure]))

(defn v1-app
  [query-fn]
  (compojure/routes
   (compojure/GET "/state-overview" []
     (state-overview/state-overview-app query-fn))))

(defn build-app
  [query-fn]
  (compojure/routes
   (compojure/context "/v1" [] (v1-app query-fn))))

