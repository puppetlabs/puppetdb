(ns puppetlabs.puppetdb.metrics.core
  (:require [metrics.reporters.jmx :refer [reporter]]
            [metrics.core :refer [new-registry]]))

(defn new-metrics [domain]
  (let [registry (new-registry)]
    {:registry registry
     :reporter (reporter registry {:domain domain})}))

(def metrics-registries {:mq (new-metrics "puppetlabs.puppetdb.mq")
                         :dlo (new-metrics "puppetlabs.puppetdb.dlo")
                         :http (new-metrics "puppetlabs.puppetdb.http")
                         :population (new-metrics "puppetlabs.puppetdb.population")
                         :storage (new-metrics "puppetlabs.puppetdb.storage")
                         :database (new-metrics "puppetlabs.puppetdb.database")})
