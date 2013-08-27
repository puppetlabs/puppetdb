(ns com.puppetlabs.puppetdb.http.v3
  (:use [com.puppetlabs.puppetdb.http.v3.command :only (command-app)]
        [com.puppetlabs.puppetdb.http.v3.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v3.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v3.node :only (node-app)]
        [com.puppetlabs.puppetdb.http.v3.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v3.metrics :only (metrics-app)]
        [com.puppetlabs.puppetdb.http.v3.version :only (version-app)]
        [net.cgrand.moustache :only (app)]))

(def v3-app
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
