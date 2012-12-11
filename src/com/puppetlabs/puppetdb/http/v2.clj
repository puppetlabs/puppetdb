(ns com.puppetlabs.puppetdb.http.v2
  (:use [com.puppetlabs.puppetdb.http.v2.command :only (command-app)]
        [com.puppetlabs.puppetdb.http.v2.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v2.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v2.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v2.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v2.metrics :only (metrics-app)]
        [com.puppetlabs.puppetdb.http.v2.version :only (version-app)]
        [net.cgrand.moustache :only (app)]))

(def v2-app
  (app
    ["commands" &]
    {:any command-app}

    ["facts" &]
    {:any facts-app}

    ["fact-names" &]
    {:any fact-names-app}

    ["nodes" &]
    {:any node-app}

    ["resources" &]
    {:any resources-app}

    ["metrics" &]
    {:any metrics-app}

    ["version" &]
    {:any version-app}))
