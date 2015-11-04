(ns puppetlabs.puppetdb.http.index
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-paging-options]]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn routes
  [version optional-handlers]
  (let [param-spec {:optional paging/query-params
                    :required ["query"]}]
    (app
     []
     (http-q/query-route version param-spec optional-handlers))))

(defn index-app
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      wrap-with-paging-options))
