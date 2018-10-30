(ns puppetlabs.puppetdb.admin-clean-test
  (:require [clj-time.core :as time]
            [clojure.math.combinatorics :refer [combinations]]
            [clojure.test :refer :all]
            [metrics.counters :as counters]
            [metrics.gauges :as gauges]
            [metrics.timers :as timers]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.config :as conf]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.testutils :refer [default-timeout-ms]]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db*
                     clear-db-for-testing!
                     with-test-db]]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*server* with-pdb-with-no-gc]]
            [puppetlabs.puppetdb.time :as pdbtime]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]])
  (:import
   [java.util.concurrent CyclicBarrier TimeUnit]))

(defn await-a-while [x]
  (.await x default-timeout-ms TimeUnit/MILLISECONDS))

(deftest clean-command-validation
  (are [x] (#'admin/validate-clean-command {:command "clean"
                                            :version 1
                                            :payload x})
       []
       ["purge_nodes"]
       [["purge_nodes" {:batch_limit 100}]]
       ["expire_nodes" ["purge_nodes" {:batch_limit 100}] "purge_reports"]))

(defn- post-admin [path form]
  (svc-utils/post (svc-utils/admin-url-str (str "/" path))
                  form))

(defn- checked-admin-post [path form]
  (let [result (post-admin path form)]
    (is (= http/status-ok (:status result)))
    (when-not (= http/status-ok (:status result))
      (binding [*out* *err*]
        (clojure.pprint/pprint result)
        (println "Response body:")
        (println (slurp (:body result)))))))

(defn- clean-cmd [what]
  {:command "clean" :version 1 :payload what})

(defn- post-clean [what]
  (post-admin "cmd" (clean-cmd what)))

(deftest admin-clean-basic
  (with-pdb-with-no-gc
    (is (= http/status-ok (:status (post-clean []))))
    (is (= http/status-ok (:status (post-clean ["expire_nodes"]))))
    (is (= http/status-ok (:status (post-clean ["purge_nodes"]))))
    (is (= http/status-ok (:status (post-clean ["purge_reports"]))))
    (is (= http/status-ok (:status (post-clean ["gc_packages"]))))
    (is (= http/status-ok (:status (post-clean ["other"]))))
    (is (= http/status-bad-request (:status (post-clean ["?"]))))))

(deftest admin-clean-competition
  (with-pdb-with-no-gc
    (let [orig-clean cli-svc/clean-up
          in-clean (CyclicBarrier. 2)
          test-finished (CyclicBarrier. 2)
          cleanup-result (promise)]
      (with-redefs [cli-svc/clean-up (fn [& args]
                                       (await-a-while in-clean)
                                       (await-a-while test-finished)
                                       (let [result (apply orig-clean args)]
                                            (deliver cleanup-result result)
                                            result))]
        (utils/noisy-future (checked-admin-post "cmd" (clean-cmd [])))
        (try
          (await-a-while in-clean)
          (is (= http/status-conflict (:status (post-clean []))))
          (finally
            (await-a-while test-finished)
            (is (not= ::timed-out
                      (deref cleanup-result 120000 ::timed-out)))))))))

(defn- clean-status []
  (gauges/value (:cleaning cli-svc/admin-metrics)))

(deftest admin-clean-status
  (with-pdb-with-no-gc
    (let [orig-clean @#'cli-svc/clean-puppetdb
          after-clean (CyclicBarrier. 2)
          orig-clear @#'cli-svc/clear-clean-status!
          before-clear (CyclicBarrier. 2)
          after-test (CyclicBarrier. 2)
          after-clear (CyclicBarrier. 2)]
      (with-redefs [cli-svc/clean-puppetdb (fn [& args]
                                             (apply orig-clean args)
                                             (await-a-while after-clean))
                    cli-svc/clear-clean-status! (fn [& args]
                                                  (await-a-while before-clear)
                                                  (await-a-while after-test)
                                                  (apply orig-clear args)
                                                  (await-a-while after-clear))]
        (doseq [what (combinations ["expire_nodes"
                                    "purge_nodes"
                                    ["purge_nodes" {"batch_limit" 10}]
                                    "purge_reports"
                                    "gc_packages"
                                    "other"]
                                   3)]
          (let [expected (-> what
                             cli-svc/reduce-clean-request
                             cli-svc/reduced-clean-request->status)]
            (utils/noisy-future (checked-admin-post "cmd" (clean-cmd what)))
            (try
              (await-a-while before-clear)
              (is (= expected (clean-status)))
              (finally
                (await-a-while after-test)
                (await-a-while after-clear)
                (await-a-while after-clean)))))))))

(defn purgeable-nodes [node-purge-ttl]
  (let [horizon (pdbtime/to-timestamp (time/ago node-purge-ttl))]
    (jdbc/query-to-vec
     "select * from certnames where deactivated < ? or expired < ?"
     horizon horizon)))

(deftest node-purge-batch-limits
  (with-pdb-with-no-gc
    (let [config (-> *server* (get-service :ConfigService) conf/get-config)
          orig-clean @#'cli-svc/clean-puppetdb
          after-clean (CyclicBarrier. 2)
          node-purge-ttl (get-in config [:database :node-purge-ttl])
          deactivation-time (pdbtime/to-timestamp (time/ago node-purge-ttl))
          clean (fn [req]
                  (utils/noisy-future
                   (checked-admin-post "cmd" (clean-cmd req)))
                  (await-a-while after-clean))]
      (with-redefs [cli-svc/clean-puppetdb (fn [& args]
                                             (apply orig-clean args)
                                             (await-a-while after-clean))]
        (doseq [[batches expected-remaining] [[nil 0] ; i.e. purge everything
                                              [[7] 3]
                                              [[3 4] 3]
                                              [[100] 0]]]
          (clear-db-for-testing!)
          (migrate! *db*)
          (dotimes [i 10]
            (let [name (str "foo-" i)]
              (scf-store/add-certname! name)
              (scf-store/deactivate-node! name deactivation-time)))
          (if-not batches
            (clean ["purge_nodes"])
            (doseq [limit batches]
              (clean [["purge_nodes" {"batch_limit" limit}]])))
          (is (= expected-remaining
                 (count (purgeable-nodes node-purge-ttl)))))))))

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
    (doseq [requested (combinations ["expire_nodes" "purge_nodes"
                                     "purge_reports" "gc_packages" "other"]
                                    3)]
      (let [requested (set requested)
            before (get-counts)
            expected (inc-requested before requested)]
        (checked-admin-post "cmd" (clean-cmd requested))
        (is (= expected (get-counts)))))))

(deftest admin-clean-counts (check-counts clean-counts))
(deftest admin-clean-timers (check-counts clean-timer-counts))
