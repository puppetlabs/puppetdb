(ns com.puppetlabs.puppetdb.http.v1
  (:use [com.puppetlabs.puppetdb.http.v1.command :only (command-app)]
        [com.puppetlabs.puppetdb.http.v1.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v1.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v1.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v1.status :only (status-app)]
        [com.puppetlabs.puppetdb.http.v1.metrics :only (metrics-app)]
        [com.puppetlabs.puppetdb.http.v1.version :only (version-app)]
        [net.cgrand.moustache :only (app)]))

(def v1-app
  (app
    ["commands" &]
    {:any command-app}

    ["facts" &]
    {:any facts-app}

    ["nodes" &]
    {:any node-app}

    ["resources" &]
    {:any resources-app}

    ["status" &]
    {:any status-app}

    ["metrics" &]
    {:any metrics-app}

    ["version" &]
    {:any version-app}))
