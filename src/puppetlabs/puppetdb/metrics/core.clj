(ns puppetlabs.puppetdb.metrics.core
  (:require [metrics.reporters.jmx :refer [reporter]]
            [metrics.core :refer [new-registry]]))

(defn new-metrics [domain & {:keys [jmx?] :or {jmx? true}}]
  (let [registry (new-registry)]
    (if-not jmx?
      {:registry registry}
      {:registry registry
       :reporter (reporter registry {:domain domain})})))

(def metrics-registries {:admin (new-metrics "puppetlabs.puppetdb.admin")
                         :benchmark (new-metrics "puppetlabs.puppetdb.benchmark")
                         :mq (new-metrics "puppetlabs.puppetdb.mq")
                         :dlo (new-metrics "puppetlabs.puppetdb.dlo")
                         :http (new-metrics "puppetlabs.puppetdb.http")
                         :population (new-metrics "puppetlabs.puppetdb.population")
                         :storage (new-metrics "puppetlabs.puppetdb.storage")
                         :database (new-metrics "puppetlabs.puppetdb.database")})

(defn keyword->metric-name
  "Creates a metric name from a keyword. Applies the prefix so that it
  can be grouped in a way that is easy to view as a set of JMX MBeans"
  [prefix metric-name]
  (->> metric-name
       (conj prefix)
       (mapv name)))
