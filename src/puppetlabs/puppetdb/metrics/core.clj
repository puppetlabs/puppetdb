(ns puppetlabs.puppetdb.metrics.core
  (:require [clj-http.util :refer [url-encode]]
            [cheshire.custom :refer [JSONable]]
            [clojure.java.jmx :as jmx]
            [metrics.reporters.jmx :refer [reporter]]
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

(defn filter-mbean
  "Converts an mbean to a map. For attributes that can't be converted to JSON,
  return a string representation of the value."
  [mbean]
  {:post [(map? %)]}
  (into {} (for [[k v] mbean]
             (cond
              ;; Nested structures should themselves be filtered
              (map? v)
              [k (filter-mbean v)]

              (instance? java.util.HashMap v)
              [k (filter-mbean (into {} v))]

              ;; Cheshire can serialize to JSON anything that
              ;; implements the JSONable protocol
              (satisfies? JSONable v)
              [k v]

              :else
              [k (str v)]))))

(defn all-mbean-names
  "Return a seq of all mbeans names"
  []
  {:post [(coll? %)]}
  (map str (jmx/mbean-names "*:*")))

(defn mbean-names
  "Return a map of mbean name to a link that will retrieve the attributes"
  []
  (->> (all-mbean-names)
       (map #(vector % (format "/mbeans/%s" (url-encode %))))
       (into (sorted-map))))

(defn get-mbean
  "Returns the attributes of a given MBean"
  [name]
  (when (some #(= name %) (all-mbean-names))
    (filter-mbean
     (jmx/mbean name))))
