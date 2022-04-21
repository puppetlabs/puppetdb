(ns puppetlabs.puppetdb.cli.fact-storage-benchmark
  (:require [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.cli.util :refer [exit run-cli-cmd]]
            [puppetlabs.puppetdb.time :refer [now]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.core.async :as async]))


(defn sleep-until-queue-empty [base-url]
  (let [queue-depth-metrics (-> (client/get-metric base-url "puppetlabs.puppetdb.mq:name=global.depth")
                                (json/parse-string true))]
    (when-not (zero? (:Count queue-depth-metrics))
      (Thread/sleep 50)
      (recur base-url))))

(defn gen-facts [certname generation-num
                 {:keys [shared-static-facts unique-static-facts unique-changing-facts]}]
  (let [facts (concat
               (for [n (range shared-static-facts)]
                 [(str "shared-static-" n) (str "shared-static-" n)])
               (for [n (range unique-static-facts)]
                 [(str "unique-static-" n) (str "unique-static-" certname "-" n)])
               (for [n (range unique-changing-facts)]
                 [(str "changing-" n) (str "changing-static-" certname "-" n "-" generation-num)])
               ;; some facts with various degrees of selectivity for testing query perf.
               [["two-values" (mod (hash certname) 2)]
                ["three-values" (mod (hash certname) 3)]
                ["four-values" (mod (hash certname) 4)]
                ["hundred-values" (mod (hash certname) 100)]])]
    {:certname certname
     :environment "production"
     :producer_timestamp (now)
     :producer "micro-bench"
     :values (into {} facts)}))

(defn submit-facts-for-prefix [pdb-hostname prefix {:keys [num-generations num-nodes] :as opts}]
  (do
    (doseq [generation-num (range num-generations)]
      (binding [*out* *err*]
        (println "Submitting facts with prefix" prefix "for generation" generation-num "..."))
      (doseq [certname (map (partial str "group-" prefix "-host-")
                            (range num-nodes))]
        (let [facts (gen-facts certname generation-num opts)]
          (client/submit-facts (utils/pdb-cmd-base-url pdb-hostname 8080 :v1)
                               certname
                               5
                               facts))))))

(defn parallel-submit-facts [pdb-hostname {:keys [num-threads _num-nodes] :as opts}]
  (let [opts-for-thread (update opts :num-nodes #(Math/floor (/ % num-threads)))
        threads (->> (range num-threads)
                     (map (fn [thread-num]
                            (async/thread
                              (submit-facts-for-prefix pdb-hostname
                                                       (str thread-num)
                                                       opts-for-thread))))
                     doall)]

    (doseq [t threads]
      (async/<!! t))

    (binding [*out* *err*]
      (println "Done submitting, waiting for processing..."))

    (sleep-until-queue-empty (utils/metrics-base-url pdb-hostname 8080 :v1))

    (binding [*out* *err*]
      (println "done"))))

(defn run-fact-submission-benchmark [test-name pdb-hostname opts]
  (let [start (. System (nanoTime))]
    (parallel-submit-facts pdb-hostname opts)
    (println (str test-name ": " (/ (double (- (. System (nanoTime)) start)) 1000000.0)))))

;; Test profiles
;; - num-threads: how many synthesis + submission threads to run
;; - num-nodes: total number of nodes to synthesize
;; - num-generations: how many puppet runs to simulate
;; - shared-static-facts: number of facts to generate that are shared between
;;   nodes and never change
;; - unique-static-facts: number of facts that are unique per-node but never
;;   change.
;; - unique-changing-facts: number of facts that are unique per-node and change
;;   every generation

(def test-configs
  [{:name "small"
    :num-threads 5
    :num-nodes 100
    :num-generations 2
    :shared-static-facts 100
    :unique-static-facts 100
    :unique-changing-facts 50}

   {:name "medium"
    :num-threads 5
    :num-nodes 1000
    :num-generations 20
    :shared-static-facts 200
    :unique-static-facts 780
    :unique-changing-facts 20}

   {:name "large"
    :num-threads 5
    :num-nodes 10000
    :num-generations 2
    :shared-static-facts 100
    :unique-static-facts 100
    :unique-changing-facts 50}
   ])

(defn unique-index-by [rel k]
  (->> rel
       (map (fn [row] [(get row k) row]))
       (into {})))

(defn benchmark
  "Runs benchmark as directed by the command line args."
  [args]
  (let [[pdb-hostname & [test-name]] args]
    (binding [*out* *err*]
      (println "Running facts benchmark against puppetdb at" pdb-hostname))

    (let [tests (if test-name [test-name] (map :name test-configs))
          test-configs-by-name (unique-index-by test-configs :name)]
      (doseq [t tests]
        (binding [*out* *err*]
          (println "Running test:" t))
        (run-fact-submission-benchmark t pdb-hostname (test-configs-by-name t))))
    0))

(defn cli
  "Runs the fact-storage-benchmark command as directed by the command
  line args and returns an appropriate exit status."
  [args]
  (run-cli-cmd #(benchmark args)))

(defn -main [& args]
  (exit (cli args)))
