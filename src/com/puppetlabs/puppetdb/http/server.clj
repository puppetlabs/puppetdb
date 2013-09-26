;; ## REST server
;;
;; Consolidates our disparate REST endpoints into a single Ring
;; application.

(ns com.puppetlabs.puppetdb.http.server
  (:require [clojure.tools.logging :as log])
  (:use [com.puppetlabs.puppetdb.http.v1 :only (v1-app)]
        [com.puppetlabs.puppetdb.http.v2 :only (v2-app)]
        [com.puppetlabs.puppetdb.http.v3 :only (v3-app)]
        [com.puppetlabs.puppetdb.http.experimental :only (experimental-app)]
        [com.puppetlabs.middleware :only
         (wrap-with-debug-logging wrap-with-authorization wrap-with-certificate-cn wrap-with-globals wrap-with-metrics wrap-with-default-body)]
        [com.puppetlabs.http :only (leading-uris json-response)]
        [net.cgrand.moustache :only (app)]
        [ring.middleware.resource :only (wrap-resource)]
        [ring.middleware.params :only (wrap-params)]
        [ring.util.response :only (redirect header)]))

(defn deprecated-app
  [app msg request]
  (let [result (app request)]
    (log/warn msg)
    (header result "X-Deprecation" msg)))

(defn backward-compatible-v1-app
  [request]
  (deprecated-app
    v1-app
    (format "Use of unversioned APIs is deprecated; please use /v1%s" (:uri request))
    request))

(defn deprecated-v1-app
  [request]
  (deprecated-app
    v1-app
    "v1 query API is deprecated and will be removed in an upcoming release.  Please upgrade to v3."
    request))

(def routes
  (app
    ["v1" &]
    {:any deprecated-v1-app}

    ["v2" &]
    {:any v2-app}

    ["v3" &]
    {:any v3-app}

    ["experimental" &]
    {:any experimental-app}

    [""]
    {:get (constantly (redirect "/dashboard/index.html"))}

    ;; Mount the v1 app at / for backward compatibility with unversioned API
    [&]
    {:any backward-compatible-v1-app}))

(defn build-app
  "Generate a Ring application that handles PuppetDB requests

  `options` is a list of keys and values where keys can be the following:

  * `globals` - a map containing global state useful to request handlers.

  * `authorized?` - a function that takes a request and returns a
    truthy value if the request is authorized. If not supplied, we default
    to authorizing all requests."
  [& options]
  (let [opts (apply hash-map options)]
    (-> routes
        (wrap-resource "public")
        (wrap-params)
        (wrap-with-authorization (opts :authorized? (constantly true)))
        (wrap-with-certificate-cn)
        (wrap-with-default-body)
        (wrap-with-metrics (atom {}) leading-uris)
        (wrap-with-globals (opts :globals))
        (wrap-with-debug-logging))))
