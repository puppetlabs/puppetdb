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
