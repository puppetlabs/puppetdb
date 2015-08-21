(ns puppetlabs.pe-puppetdb-extensions.server
  (:require [puppetlabs.pe-puppetdb-extensions.state-overview :as state-overview]
            [puppetlabs.puppetdb.middleware :refer
             [wrap-with-puppetdb-middleware wrap-with-globals]]
            [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [compojure.core :refer [GET ANY routes] :as compojure]
            [compojure.route :as route]))

(defn v1-app
  [query-fn]
  (routes (GET "/state-overview" [] (state-overview/state-overview-app query-fn))))

(defn build-app
  "Generate a Ring application that handles PuppetDB requests

  `globals` is a map containing global state useful
   to request handlers which may contain the following:

  * `authorizer` - a function that takes a request and returns a
    :authorized if the request is authorized, or a user-visible reason if not.
    If not supplied, we default to authorizing all requests."
  [query-fn]
  (routes (compojure/context "/v1" [] (v1-app query-fn))
          (route/not-found "Not Found")))
