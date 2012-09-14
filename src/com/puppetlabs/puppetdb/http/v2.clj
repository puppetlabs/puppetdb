(ns com.puppetlabs.puppetdb.http.v2
  (:use [com.puppetlabs.puppetdb.http.v2.command :only (command-app)]
        [com.puppetlabs.puppetdb.http.v2.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v2.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v2.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v2.status :only (status-app)]
        [com.puppetlabs.puppetdb.http.v2.metrics :only (metrics-app)]
        [com.puppetlabs.puppetdb.http.v2.version :only (version-app)]
        [net.cgrand.moustache :only (app)]))

(def v2-app
  (app
    ["commands"]
    {:post command-app}

    ["facts" node]
    {:get (fn [req]
            (facts-app (assoc-in req [:params "node"] node)))}

    ["nodes"]
    {:get node-app}

    ["resources"]
    {:get resources-app}

    ["status" &]
    {:get status-app}

    ["metrics" &]
    {:get metrics-app}

    ["version" &]
    {:get version-app}))
