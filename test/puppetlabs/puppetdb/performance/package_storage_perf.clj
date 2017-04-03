(ns puppetlabs.puppetdb.performance.package-storage-perf
  (:require [puppetlabs.kitchensink.core :as ks]
            [clojure.test.check.generators :as gen]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.command :as cmd]
            [clj-time.core :as t]
            [com.climate.claypoole :as cp]))

(defn store-facts [db certname values packages-map]
  (let [package-tuples (map (fn [[name version]]
                              [name (str version) "apt"])
                            packages-map)]
   (cmd/call-with-quick-retry
    4
    #(cmd/replace-facts* {:id 42
                          :received "2015-12-01"
                          :payload {:certname certname
                                    :values values
                                    :package_inventory package-tuples
                                    :environment "production"
                                    :timestamp "2015-12-01"
                                    :producer_timestamp "2015-12-01"
                                    :producer "master"}}
                         (t/now)
                         db))))

;; 1. Initial storage
;;   1. no packages
;;        generate one set of keys, uniform values, mutate 10% once
;;   2. with packages
;; 2. fact updates
;;   1. no packages: select 10% of fact paths, change those values, no packages
;;   2. adding new packages
;;   3. upgrading a single package across the fleet
;;   4. upgrading all packages on every node

(defn mutate-facts [value-map changing-fact-names salt]
  (reduce (fn [facts fact-name-to-change]
            (update facts fact-name-to-change
                    (comp ks/utf8-string->sha1 (partial str salt))))
          value-map
          changing-fact-names))


(defn mutate-package-names [packages-map node-specific-package-count salt]
  (->> packages-map
       (map-indexed (fn [n [name version]]
                      [(if (< n node-specific-package-count)
                         (str name "-" salt)
                         name)
                       version]))
       (into {})))


(defn upgrade-packages [packages-map package-upgrade-count iterations]
  (->> packages-map
       reverse
       (map-indexed (fn [n [name version]]
                      [name
                       (if (< n package-upgrade-count)
                         (+ n iterations 1)
                         version)]))
       (into {})))

(defn call-with-timing [f]
  (let [start (System/currentTimeMillis)]
    (f)
    (- (System/currentTimeMillis) start)))

(defn run-fact-storage-bench [{:keys [certname-count
                                      fact-count
                                      changing-fact-count
                                      update-iterations
                                      package-count
                                      node-specific-package-count
                                      package-upgrade-count
                                      threads]}]
  (with-test-db
    (let [all-fact-names (->> (range)
                              (map (comp ks/utf8-string->sha1 str hash))
                              (take fact-count))
          initial-facts (zipmap all-fact-names
                                (->> all-fact-names
                                     (map (partial str "val-for-"))))
          changing-fact-names (take changing-fact-count all-fact-names)
          initial-packages-map (zipmap (->> (range)
                                            (map (partial str "pkg-"))
                                            (take package-count))
                                       (repeat 1))
          certnames (->> (range)
                         (map (partial str "-agent"))
                         (take certname-count))
          pool (cp/threadpool threads)

          initial-storage-ms
          (call-with-timing
           #(->> certnames
                 (cp/pmap pool
                          (fn [certname]
                            (store-facts *db* certname
                                         (mutate-facts initial-facts
                                                       changing-fact-names
                                                       certname)
                                         (mutate-package-names initial-packages-map
                                                               node-specific-package-count
                                                               certname))))
                 dorun))

          update-fact-ms
          (call-with-timing
           #(doseq [iteration (range update-iterations)]
              (->> certnames
                   (cp/pmap pool
                            (fn [certname]
                              (store-facts *db* certname
                                           (mutate-facts initial-facts
                                                         changing-fact-names
                                                         (str certname iteration))
                                           (-> initial-packages-map
                                               (mutate-package-names node-specific-package-count
                                                                     certname)
                                               (upgrade-packages package-upgrade-count
                                                                 update-iterations)))))
                   dorun)))]
      {:initial (float (/ initial-storage-ms certname-count))
       :update (float (/ update-fact-ms (* update-iterations certname-count)))})))

(defn single-threaded-fact-storage []
  (run-fact-storage-bench
   {:certname-count 100
    :fact-count 250
    :changing-fact-count 25
    :update-iterations 3
    :package-count 0
    :node-specific-package-count 0
    :package-upgrade-count 0
    :threads 1}))

(defn concurrent-fact-storage []
  (run-fact-storage-bench
   {:certname-count 300
    :fact-count 250
    :changing-fact-count 25
    :update-iterations 3
    :package-count 0
    :node-specific-package-count 0
    :package-upgrade-count 0
    :threads 8}))

(defn single-threaded-package-storage []
  (run-fact-storage-bench
   {:certname-count 100
    :fact-count 250
    :changing-fact-count 25
    :update-iterations 3
    :package-count 200
    :node-specific-package-count 15
    :package-upgrade-count 20
    :threads 1}))


(defn concurrent-package-storage []
  (run-fact-storage-bench
   {:certname-count 300
    :fact-count 250
    :changing-fact-count 25
    :update-iterations 3
    :package-count 200
    :node-specific-package-count 15
    :package-upgrade-count 20
    :threads 8}))
