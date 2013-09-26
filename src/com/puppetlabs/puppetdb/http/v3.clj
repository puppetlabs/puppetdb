(ns com.puppetlabs.puppetdb.http.v3
  (:use [com.puppetlabs.puppetdb.http.v3.command :only (command-app)]
        [com.puppetlabs.puppetdb.http.v3.facts :only (facts-app)]
        [com.puppetlabs.puppetdb.http.v3.fact-names :only (fact-names-app)]
        [com.puppetlabs.puppetdb.http.v3.nodes :only (node-app)]
        [com.puppetlabs.puppetdb.http.v3.resources :only (resources-app)]
        [com.puppetlabs.puppetdb.http.v3.metrics :only (metrics-app)]
        [com.puppetlabs.puppetdb.http.v3.version :only (version-app)]
        [com.puppetlabs.puppetdb.http.v3.catalogs :only (catalog-app)]
        [com.puppetlabs.puppetdb.http.v3.events :only (events-app)]
        [com.puppetlabs.puppetdb.http.v3.reports :only (reports-app)]
        [com.puppetlabs.puppetdb.http.v3.event-counts :only (event-counts-app)]
        [com.puppetlabs.puppetdb.http.v3.aggregate-event-counts :only (aggregate-event-counts-app)]
        [com.puppetlabs.puppetdb.http.v3.server-time :only (server-time-app)]
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
    {:any version-app}

    ["catalogs" &]
    {:any catalog-app}

    ["events" &]
    {:any events-app}

    ["event-counts" &]
    {:any event-counts-app}

    ["aggregate-event-counts" &]
    {:any aggregate-event-counts-app}

    ["reports" &]
    {:any reports-app}

    ["server-time" &]
    {:any server-time-app}))
