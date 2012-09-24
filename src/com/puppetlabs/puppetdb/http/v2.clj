(ns com.puppetlabs.puppetdb.http.v2
  (:use [com.puppetlabs.puppetdb.http.v2.event :only (events-app)]
        [com.puppetlabs.puppetdb.http.v2.event-group :only (event-groups-app)]
        [net.cgrand.moustache :only (app)]))

(def v2-app
  (app
    ["events"]
    {:get events-app}

    ["event-groups"]
    {:get event-groups-app}))
