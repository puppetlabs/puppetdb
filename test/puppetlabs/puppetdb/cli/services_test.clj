(ns puppetlabs.puppetdb.cli.services-test
  (:require [clojure.set :refer [subset?]]
            [puppetlabs.http.client.sync :as pl-http]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.cli.util :refer [err-exit-status]]
            [puppetlabs.puppetdb.command.constants :as cmd-consts]
            [puppetlabs.puppetdb.scf.partitioning
             :refer [get-temporal-partitions]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.puppetdb.cli.services :as svcs :refer :all]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db* clear-db-for-testing! with-test-db
                     with-unconnected-test-db]]
            [puppetlabs.puppetdb.testutils.cli
             :refer [example-certname example-report get-factsets]]
            [puppetlabs.puppetdb.command :refer [enqueue-command]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [initialize-schema]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.time :as time :refer [now to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.meta.version :as version]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*base-url*
                     *server*
                     call-with-puppetdb-instance
                     call-with-single-quiet-pdb-instance
                     create-temp-config
                     sync-command-post
                     with-pdb-with-no-gc]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.puppetdb.testutils
             :refer [block-until-results default-timeout-ms temp-file]]
            [puppetlabs.puppetdb.cheshire :as json]
            [overtone.at-at :refer [mk-pool stop-and-reset-pool!]]
            [puppetlabs.puppetdb.testutils.queue :as tqueue])
  (:import
   [clojure.lang ExceptionInfo]
   (java.util.concurrent CyclicBarrier)
   [java.util.concurrent.locks ReentrantLock]))

(deftest update-checking
  (let [config-map {:global {:product-name "puppetdb"
                             :update-server "update-server!"}}
        shutdown-for-ex (fn [ex]
                          (binding [*out* *err*]
                            (println "Ignoring shutdown exception during services tests.")))]

    (testing "should check for updates if running as puppetdb"
      (with-redefs [version/check-for-updates! (constantly "Checked for updates!")]
        (let [job-pool-test (mk-pool)
              recurring-job-checkin (maybe-check-for-updates config-map {} job-pool-test
                                                             shutdown-for-ex)]
          (is (= 86400000 (:ms-period recurring-job-checkin))
              "should run once a day")
          (is (= true @(:scheduled? recurring-job-checkin))
              "should be scheduled to run")
          (is (= 0 (:initial-delay recurring-job-checkin))
              "should run on start up with no delay")
          (is (= "A reoccuring job to checkin the PuppetDB version" (:desc recurring-job-checkin))
              "should have a description of the job running")
          (stop-and-reset-pool! job-pool-test))))

    (testing "should skip the update check if running as pe-puppetdb"
      (with-log-output log-output
        (maybe-check-for-updates (assoc-in config-map [:global :product-name] "pe-puppetdb")
                                 {} nil shutdown-for-ex)
        (is (= 1 (count (logs-matching #"Skipping update check on Puppet Enterprise" @log-output))))))))

(defn- check-service-query
  [version q pagination check-result]
  (let [pdb-service (get-service svc-utils/*server* :PuppetDBServer)
        results (atom nil)
        before-slurp? (atom nil)
        after-slurp? (atom nil)]
    (query pdb-service version q pagination
           (fn [result-set]
             ;; We evaluate the first element from lazy-seq just to check if DB query was successful or not
             ;; so we have to ensure the first element and the rest have been realized, not just the first
             ;; element on its own.
             (reset! before-slurp? (and (realized? result-set) (realized? (rest result-set))))
             (reset! results (vec result-set))
             (reset! after-slurp? (and (realized? result-set) (realized? (rest result-set))))))
    (is (false? @before-slurp?))
    (check-result @results)
    (is (true? @after-slurp?))))

(deftest query-via-puppdbserver-service
  (svc-utils/with-single-quiet-pdb-instance
    (let [dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          query-fn (partial query (get-service svc-utils/*server* :PuppetDBServer))]
      (enqueue-command dispatcher
                       "replace facts"
                       4
                       "foo.local"
                       nil
                       (tqueue/coerce-to-stream
                        {:certname "foo.local"
                         :environment "DEV"
                         :values {:foo "the foo"
                                  :bar "the bar"
                                  :baz "the baz"}
                         :producer_timestamp (to-string (now))})
                       "")

      @(block-until-results 200 (first (get-factsets "foo.local")))

      (check-service-query
       :v4 ["from" "facts" ["=" "certname" "foo.local"]]
       nil
       (fn [result]
         (is (= #{{:value "the baz",
                   :name "baz",
                   :environment "DEV",
                   :certname "foo.local"}
                  {:value "the bar",
                   :name "bar",
                   :environment "DEV",
                   :certname "foo.local"}
                  {:value "the foo",
                   :name "foo",
                   :environment "DEV",
                   :certname "foo.local"}}
                (set result))))))))

(deftest pagination-via-puppdbserver-service
  (svc-utils/with-puppetdb-instance
    (let [dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          query-fn (partial query (get-service svc-utils/*server* :PuppetDBServer))]
      (enqueue-command dispatcher
                       "replace facts"
                       4
                       "foo.local"
                       nil
                       (tqueue/coerce-to-stream
                        {:certname "foo.local"
                         :environment "DEV"
                         :values {:a "a" :b "b" :c "c"}
                         :producer_timestamp (to-string (now))})
                       "")

      @(block-until-results 200 (first (get-factsets "foo.local")))
      (let [exp ["a" "b" "c"]
            rexp (reverse exp)]
        (doseq [order [:ascending :descending]
                offset (range (dec (count exp)))
                limit (range 1 (count exp))]
          (let [expected (take limit
                               (drop offset (if (= order :ascending) exp rexp)))]
            (check-service-query
             :v4 ["from" "facts" ["=" "certname" "foo.local"]]
             {:order_by [[:name order]]
              :offset offset
              :limit limit}
             (fn [result]
               (is (= (map #(hash-map :name % :value %
                                      :environment "DEV",
                                      :certname "foo.local")
                           expected)
                      result))))))))))

(deftest api-retirements
  (svc-utils/with-puppetdb-instance
    (letfn [(ping [v]
              (-> (svc-utils/pdb-query-url)
                  (assoc :version v)
                  (svc-utils/create-url-str "/facts")
                  svc-utils/get))
            (retirement-response? [v response]
              (and (= 404 (:status response))
                   (= (format "The %s API has been retired; please use v4"
                              (name v))
                      (:body  response))))]
      (is (= 200 (:status (ping :v4))))
      (doseq [v [:v1 :v2 :v3]]
        (testing (format "%s requests are refused" (name v)))
        (is (retirement-response? v (ping v)))))))

(defn make-https-request-with-allowlisted-host [allowlisted-host]
  (let [allowlist-file (temp-file "allowlist-log-reject")
        cert-config {:ssl-cert "test-resources/localhost.pem"
                     :ssl-key "test-resources/localhost.key"
                     :ssl-ca-cert "test-resources/ca.pem"}]
    (spit allowlist-file allowlisted-host)
    (with-test-db
      (svc-utils/call-with-puppetdb-instance
       (-> (svc-utils/create-temp-config)
           (assoc :database *db*)
           (assoc :jetty (merge cert-config
                                {:ssl-port 0
                                 :ssl-host "0.0.0.0"
                                 :ssl-protocols "TLSv1,TLSv1.1,TLSv1.2"}))
           (assoc-in [:puppetdb :certificate-allowlist] (str allowlist-file)))
       (fn []
         (pl-http/get (str (utils/base-url->str (assoc *base-url* :version :v4))
                           "/facts")
                      (merge cert-config
                             {:headers {"accept" "application/json"}
                              :as :text})))))))

(deftest cert-allowlists
  (testing "hosts not in allowlist should be forbidden"
    (let [response (make-https-request-with-allowlisted-host "bogus")]
      (is (= 403 (:status response)))
      (is (re-find #"Permission denied" (:body response)))))

  (testing "host in the cert allowlist is allowed"
    (let [response (make-https-request-with-allowlisted-host "localhost")]
      (is (= 200 (:status response)))
      (is (not (re-find #"Permission denied" (:body response)))))))

(deftest unsupported-database-triggers-shutdown
  ;; Intercept and validate both the throw from start-puppetdb and the
  ;; subsequent shutdown request from start.
  (let [service (atom nil)
        start-ex (atom nil)
        orig-start svcs/start-puppetdb
        start (fn [& args]
                (let [[context config svc get-endpts request-shutdown upgrade?] args]
                  (reset! service svc)
                  (try
                    (let [result (apply orig-start args)]
                      (reset! start-ex nil)
                      result)
                    (catch Exception ex
                      (reset! start-ex ex)
                      (throw ex)))))
        err-msg? #(re-matches #"PostgreSQL 9\.5 is no longer supported\. .*" %1)]

    ;; err-msg wrt log suppression?

    (with-redefs [sutils/db-metadata (fn [] {:database nil :version [9 5]})
                  svcs/start-puppetdb start]
      (svc-utils/with-puppetdb-instance
        true))

    (testing "unsupported db triggers unsupported-database exception"
      (let [ex (deref start-ex)
            expected-oldest scf-store/oldest-supported-db
            {:keys [kind current oldest]} (when (instance? ExceptionInfo ex)
                                            (ex-data ex))]
        (is (= ExceptionInfo (class ex)))
        (is (= ::svcs/unsupported-database kind))
        (is (= [9 5] current))
        (is (= expected-oldest oldest))))

    (testing "unsupported-database exception causes shutdown request"
      (let [opts (-> @service service-context :shutdown-request deref :opts)
            exit (:puppetlabs.trapperkeeper.core/exit opts)]
        (is (= err-exit-status (:status exit)))
        (is (some (fn [[msg out]] (err-msg? msg))
                  (:messages exit)))))))

(deftest unsupported-database-settings-trigger-shutdown
  (let [bad-setting :standard_conforming_strings
        bad-value "off"
        service (atom nil)
        start-ex (atom nil)
        orig-req svcs/request-database-settings
        request-settings #(for [{n :name :as settings} (orig-req)]
                            (if (= n (name bad-setting))
                              (assoc settings :setting bad-value)
                              settings))
        orig-start svcs/start-puppetdb
        start (fn [& args]
                (let [[context config svc get-endpts request-shutdown] args]
                  (reset! service svc)
                  (try
                    (let [result (apply orig-start args)]
                      (reset! start-ex nil)
                      result)
                    (catch Exception ex
                      (reset! start-ex ex)
                      (throw ex)))))
        err-msg? #(re-matches #"Invalid database configuration settings: 'standard_.*" %1)]

    ;; err-msg wrt log suppression?

    (with-redefs [svcs/request-database-settings request-settings
                  svcs/start-puppetdb start]
      (svc-utils/with-puppetdb-instance
        true))

    (testing "invalid-database-configuration exception thrown"
      (let [ex (deref start-ex)
            {:keys [kind failed-validation]} (when (instance? ExceptionInfo ex)
                                               (ex-data ex))]
        (is (= ExceptionInfo (class ex)))
        (is (= ::svcs/invalid-database-configuration kind))
        (is (= (get-in failed-validation [bad-setting :actual]) bad-value))))

    (testing "invalid-database-configuration exception causes shutdown request"
      (let [opts (-> @service service-context :shutdown-request deref :opts)
            exit (:puppetlabs.trapperkeeper.core/exit opts)]
        (is (= err-exit-status (:status exit)))
        (is (some (fn [[msg out]] (err-msg? msg))
                  (:messages exit)))))))

(defn purgeable-nodes [node-purge-ttl]
  (let [horizon (time/to-timestamp (time/ago node-purge-ttl))]
    (jdbc/query-to-vec
     "select * from certnames where deactivated < ? or expired < ?"
     horizon horizon)))

(deftest node-purge-gc-batch-limit
  ;; At least with the current code, it should be fine to run all the
  ;; tests with the same server, i.e. collect-garbage is only affected
  ;; by its arguments.
  (with-pdb-with-no-gc
    (let [config (-> *server* (get-service :DefaultedConfig) conf/get-config)
          node-purge-ttl (get-in config [:database :node-purge-ttl])
          deactivation-time (time/to-timestamp (time/ago node-purge-ttl))
          lock (ReentrantLock.)]
      (doseq [[limit expected-remaining] [[0 0]
                                          [7 3]
                                          [100 0]]]
        (clear-db-for-testing!)
        (initialize-schema)
        (dotimes [i 10]
          (let [name (str "foo-" i)]
            (scf-store/add-certname! name)
            (scf-store/deactivate-node! name deactivation-time)))
        (let [cfg (-> *server* (get-service :DefaultedConfig) conf/get-config)
              db-cfg (assoc (:database cfg) :node-purge-gc-batch-limit limit)
              db-lock-status (svcs/database-lock-status)]
          (collect-garbage db-cfg lock db-cfg db-lock-status
                           (db-config->clean-request db-cfg)))
        (is (= expected-remaining
               (count (purgeable-nodes node-purge-ttl))))))))


;; Test mitigation of
;; https://bugs.openjdk.java.net/browse/JDK-8176254 i.e. handling of
;; an unexpected in-flight garbage collection (via the scheduling
;; bug) during stop.  For now, just test that if a gc is in flight,
;; we wait on it if it doesn't take too long, and we proceed anyway
;; if it does.

(defn test-stop-with-periodic-gc-running [slow-gc? expected-msg-rx]
  (with-log-output log-output
    (let [gc-blocked (promise)
          gc-proceed (promise)
          gc svcs/collect-garbage
          collect-thread (promise)]
      (with-redefs [svcs/stop-gc-wait-ms (constantly (if slow-gc?
                                                       0 ;; no point in waiting
                                                       default-timeout-ms))
                    svcs/collect-garbage (fn [& args]
                                           (deliver collect-thread (Thread/currentThread))
                                           (deliver gc-blocked true)
                                           @gc-proceed
                                           (apply gc args))]
        (with-pdb-with-no-gc
          (let [pdb (get-service *server* :PuppetDBServer)
                db-cfg (-> *server* (get-service :DefaultedConfig) conf/get-config :database)
                db-lock-status (svcs/database-lock-status)
                stop-status (-> pdb service-context :stop-status)
                lock (ReentrantLock.)]
            (utils/noisy-future
             (svcs/coordinate-gc-with-shutdown db-cfg lock db-cfg db-lock-status
                                               (svcs/db-config->clean-request db-cfg)
                                               stop-status
                                               false))
            @gc-blocked
            (is (= #{@collect-thread} (:collecting-garbage @stop-status)))
            (when-not slow-gc?
              (deliver gc-proceed true))))))
    (is (= 1 (->> @log-output
                  (logs-matching expected-msg-rx)
                  count)))))

(deftest stop-waits-a-bit-for-periodic-gc
  (test-stop-with-periodic-gc-running false #"^Periodic activities halted"))

(deftest stop-ignores-gc-that-takes-too-long
  (test-stop-with-periodic-gc-running true #"^Forcibly terminating periodic activities"))

(defn change-report-time [r time]
  ;; A *very* blunt instrument, only intended to work for now on
  ;; example/reports.
  (-> (assoc r
             :producer_timestamp time
             :start_time time
             :end_time time)
      (update :logs #(mapv (fn [entry] (assoc entry :time time)) %))
      (update :resources
              (fn [resources]
                (mapv
                 (fn [res]
                   (-> res
                       (assoc :timestamp time)
                       (update :events (fn [events]
                                         (mapv #(assoc % :timestamp time)
                                               events)))))
                 resources)))))

(deftest regular-gc-drops-oldest-partitions-incrementally
  (with-unconnected-test-db
    (let [config (-> (create-temp-config)
                     (assoc :database *db*)
                     (assoc-in [:database :gc-interval] "0.01"))
          store-report #(sync-command-post (svc-utils/pdb-cmd-url)
                                           example-certname
                                           "store report"
                                           cmd-consts/latest-report-version
                                           (change-report-time example-report %))
          before-gc (CyclicBarrier. 2)
          after-gc (CyclicBarrier. 2)
          invoke-periodic (fn [f first?]
                            (.await before-gc)
                            (let [result (f first?)]
                              (.await after-gc)
                              result))]
      (with-redefs [svcs/invoke-periodic-gc invoke-periodic]
        (call-with-single-quiet-pdb-instance
         config
         (fn []
           ;; Wait for the first, full gc to finish.
           (.await before-gc)
           (.await after-gc)
           (store-report "2011-01-01T12:00:01-03:00")
           (store-report "2011-01-02T12:00:01-03:00")
           (let [report-parts (set (get-temporal-partitions "reports"))
                 event-parts (set (get-temporal-partitions "resource_events"))]

             (is (subset? #{{:table "reports_20110101z", :part "20110101z"}
                            {:table "reports_20110102z", :part "20110102z"}}
                          report-parts))

             (is (subset? #{{:table "resource_events_20110101z", :part "20110101z"}
                            {:table "resource_events_20110102z", :part "20110102z"}}
                          event-parts))

             ;; Let the gc go and make sure it only drops the oldest partition
             (.await before-gc)
             (.await after-gc)
             (is (= (->> report-parts (sort-by :table) (drop 1) set)
                    (set (get-temporal-partitions "reports"))))
             (is (= (->> event-parts (sort-by :table) (drop 1) set)
                    (set (get-temporal-partitions "resource_events"))))

             ;; Let the gc go and make sure it only drops the oldest partition
             (.await before-gc)
             (.await after-gc)
             (is (= (->> report-parts (sort-by :table) (drop 2) set)
                    (set (get-temporal-partitions "reports"))))
             (is (= (->> event-parts (sort-by :table) (drop 2) set)
                    (set (get-temporal-partitions "resource_events")))))))))))
