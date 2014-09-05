(ns puppetlabs.puppetdb.http.server
  "REST server

   Consolidates our disparate REST endpoints into a single Ring
   application."
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.v2 :refer [v2-app]]
            [puppetlabs.puppetdb.http.v3 :refer [v3-app]]
            [puppetlabs.puppetdb.http.v4 :refer [v4-app]]
            [puppetlabs.puppetdb.http.experimental :refer [experimental-app]]
            [puppetlabs.puppetdb.middleware :refer
             [wrap-with-debug-logging wrap-with-authorization wrap-with-certificate-cn
              wrap-with-globals wrap-with-metrics wrap-with-default-body]]
            [net.cgrand.moustache :refer [app]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :refer [redirect header]]))

(defn deprecated-app
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (header result "X-Deprecation" msg)))

(defn experimental-warning
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (header result "Warning" msg)))

(defn deprecated-v2-app
  [request]
  (deprecated-app
    v2-app
    "v2 query API is deprecated and will be removed in an upcoming release.  Please upgrade to v3."
    request))

(defn experimental-v4-app
  [request]
  (experimental-warning
    v4-app
    "v4 query API is experimental and may change without warning. For stability use the v3 api."
    request))

(defn routes
  [url-prefix]
  (app
    ["v2" &]
    {:any deprecated-v2-app}

    ["v3" &]
    {:any v3-app}

    ["v4" &]
    {:any experimental-v4-app}

    ["experimental" &]
    {:any experimental-app}

    [""]
    {:get (constantly (redirect (format "%s/dashboard/index.html" url-prefix)))}))

(defn build-app
  "Generate a Ring application that handles PuppetDB requests

  `options` is a list of keys and values where keys can be the following:

  * `globals` - a map containing global state useful to request handlers.

  * `authorized?` - a function that takes a request and returns a
    truthy value if the request is authorized. If not supplied, we default
    to authorizing all requests."
  [& options]
  (let [opts (apply hash-map options)]
    (-> (routes (get-in opts [:globals :url-prefix]))
        (wrap-resource "public")
        (wrap-params)
        (wrap-with-authorization (opts :authorized? (constantly true)))
        (wrap-with-certificate-cn)
        (wrap-with-default-body)
        (wrap-with-metrics (atom {}) http/leading-uris)
        (wrap-with-globals (opts :globals))
        (wrap-with-debug-logging))))
