(ns puppetlabs.puppetdb.http.index
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn index-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params
                    :required ["query"]}]
    (app
     []
     (http-q/query-route version param-spec optional-handlers))))
