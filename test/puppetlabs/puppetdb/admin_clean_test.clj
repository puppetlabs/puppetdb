(ns puppetlabs.puppetdb.admin-clean-test
  (:require [clojure.math.combinatorics :refer [combinations]]
            [clojure.test :refer :all]
            [metrics.counters :as counters]
            [metrics.gauges :as gauges]
            [metrics.timers :as timers]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*server*
                     call-with-single-quiet-pdb-instance
                     with-single-quiet-pdb-instance]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]])
  (:import
   [java.util.concurrent CyclicBarrier]))

(defmacro with-pdb-with-no-gc [& body]
  `(with-test-db
     (call-with-single-quiet-pdb-instance
      (-> (svc-utils/create-temp-config)
          (assoc :database *db*)
          (assoc-in [:database :gc-interval] 0))
      (fn []
        ~@body))))

(defn- post-admin [path form]
  (svc-utils/post (svc-utils/admin-url-str (str "/" path))
                  form))

(defn- checked-admin-post [path form]
  (let [result (post-admin path form)]
    (is (= http/status-ok (:status result)))
    (when-not (= http/status-ok (:status result))
      (binding [*out* *err*]
        (clojure.pprint/pprint result)))))

(defn- clean-cmd [what]
  {:command "clean" :version 1 :payload what})

(defn- post-clean [what]
  (post-admin "cmd" (clean-cmd what)))

(deftest admin-clean-basic
  (with-pdb-with-no-gc
    (let [pdb (get-service *server* :PuppetDBServer)]
      (is (= http/status-ok (:status (post-clean []))))
      (is (= http/status-ok (:status (post-clean ["expire_nodes"]))))
      (is (= http/status-ok (:status (post-clean ["purge_nodes"]))))
      (is (= http/status-ok (:status (post-clean ["purge_reports"]))))
      (is (= http/status-ok (:status (post-clean ["package_gc"]))))
      (is (= http/status-ok (:status (post-clean ["other"]))))
      (is (= http/status-bad-request (:status (post-clean ["?"])))))))

(deftest admin-clean-competition
  (with-pdb-with-no-gc
    (let [pdb (get-service *server* :PuppetDBServer)
          orig-clean cli-svc/clean-up
          in-clean (CyclicBarrier. 2)
          test-finished (CyclicBarrier. 2)
          cleanup-result (promise)]
      (with-redefs [cli-svc/clean-up (fn [& args]
                                       (.await in-clean)
                                       (.await test-finished)
                                       (let [result (apply orig-clean args)]
                                            (deliver cleanup-result result)
                                            result))]
        (utils/noisy-future (checked-admin-post "cmd" (clean-cmd [])))
        (try
          (.await in-clean)
          (is (= http/status-conflict (:status (post-clean []))))
          (finally
            (.await test-finished)
            (is (not= ::timed-out
                      (deref cleanup-result 120000 ::timed-out)))))))))

(defn- clean-status []
  (gauges/value (:cleaning cli-svc/admin-metrics)))

(deftest admin-clean-status
  (with-pdb-with-no-gc
    (let [pdb (get-service *server* :PuppetDBServer)
          orig-clean @#'cli-svc/clean-puppetdb
          after-clean (CyclicBarrier. 2)
          orig-clear @#'cli-svc/clear-clean-status!
          before-clear (CyclicBarrier. 2)
          after-test (CyclicBarrier. 2)
          after-clear (CyclicBarrier. 2)]
      (with-redefs [cli-svc/clean-puppetdb (fn [& args]
                                             (apply orig-clean args)
                                             (.await after-clean))
                    cli-svc/clear-clean-status! (fn [& args]
                                                  (.await before-clear)
                                                  (.await after-test)
                                                  (apply orig-clear args)
                                                  (.await after-clear))]
        (doseq [what (combinations ["expire_nodes" "purge_nodes" "purge_reports" "package_gc" "other"]
                                   3)]
          (let [expected (cli-svc/clean-options->status what)]
            (utils/noisy-future (checked-admin-post "cmd" (clean-cmd what)))
            (try
              (.await before-clear)
              (is (= expected (clean-status)))
              (finally
                (.await after-test)
                (.await after-clear)
                (.await after-clean)))))))))

(defn- inc-requested [counts requested]
  (into {}
        (for [[kind value] counts]
          [kind (if (requested kind)
                  (inc value)
                  value)])))

(defn- clean-counts []
  {"expire_nodes" (counters/value (:node-expirations cli-svc/admin-metrics))
   "purge_nodes" (counters/value (:node-purges cli-svc/admin-metrics))
   "purge_reports" (counters/value (:report-purges cli-svc/admin-metrics))
   "gc_packages" (counters/value (:package-gcs cli-svc/admin-metrics))
   "other" (counters/value (:other-cleans cli-svc/admin-metrics))})

(defn- clean-timer-counts []
  {"expire_nodes" (timers/number-recorded
                   (:node-expiration-time cli-svc/admin-metrics))
   "purge_nodes" (timers/number-recorded
                  (:node-purge-time cli-svc/admin-metrics))
   "purge_reports" (timers/number-recorded
                    (:report-purge-time cli-svc/admin-metrics))
   "gc_packages" (timers/number-recorded
                  (:package-gc-time cli-svc/admin-metrics))
   "other" (timers/number-recorded
            (:other-clean-time cli-svc/admin-metrics))})

(defn- check-counts [get-counts]
  (with-pdb-with-no-gc
    (let [pdb (get-service *server* :PuppetDBServer)]
      (doseq [requested (combinations ["expire_nodes" "purge_nodes" "purge_reports" "package_gc" "other"]
                                      3)]
        (let [requested (set requested)
              before (get-counts)
              expected (inc-requested before requested)]
          (checked-admin-post "cmd" (clean-cmd requested))
          (is (= expected (get-counts))))))))

(deftest admin-clean-counts (check-counts clean-counts))
(deftest admin-clean-timers (check-counts clean-timer-counts))
