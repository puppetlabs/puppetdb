(ns puppetlabs.puppetdb.performance.utils
  (:require [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]))

(defn time-it [f]
  (let [start (. System (nanoTime))]
    (f)
    (/ (double (- (. System (nanoTime)) start))
       1000000.0)))

(defn mean [s]
  (/ (reduce + s)
     (count s)))

(defn stats [s]
  (let [m (mean s)]
    {:mean m
     :std-dev (Math/sqrt
               (mean (map #(Math/pow (- m %) 2.0)
                          s)))}))

(defn simple-db-perf-test [{:keys [name warmup samples] :as opts} f]
  (let [samples (with-test-db
                  (->> (range (+ samples warmup))
                       (map (fn [iter] (time-it #(f iter *db*))))
                       (drop warmup)
                       (into [])))]
    (merge opts
           (stats samples)
           {:samples samples})))

