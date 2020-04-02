(ns puppetlabs.puppetdb.cli.services-test
  (:require [me.raynes.fs :as fs]
            [puppetlabs.http.client.sync :as pl-http]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.puppetdb.cli.services :as svcs :refer :all]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db* clear-db-for-testing! with-test-db]]
            [puppetlabs.puppetdb.testutils.cli :refer [get-factsets]]
            [puppetlabs.puppetdb.command :refer [enqueue-command]]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.time :as time :refer [now to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.meta.version :as version]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*base-url* *server* with-pdb-with-no-gc]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.puppetdb.testutils
             :refer [block-until-results default-timeout-ms temp-file]]
            [puppetlabs.puppetdb.cheshire :as json]
            [overtone.at-at :refer [mk-pool stop-and-reset-pool!]]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import
   [java.util.concurrent.locks ReentrantLock]))

(deftest update-checking
  (let [config-map {:global {:product-name "puppetdb"
                              :update-server "update-server!"}}]

    (testing "should check for updates if running as puppetdb"
      (with-redefs [version/check-for-updates! (constantly "Checked for updates!")]
        (let [job-pool-test (mk-pool)
              recurring-job-checkin (maybe-check-for-updates config-map {} job-pool-test)]
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
        (maybe-check-for-updates (assoc-in config-map [:global :product-name] "pe-puppetdb") {} nil)
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

(defn make-https-request-with-whitelisted-host [whitelisted-host]
  (let [whitelist-file (temp-file "whitelist-log-reject")
        cert-config {:ssl-cert "test-resources/localhost.pem"
                     :ssl-key "test-resources/localhost.key"
                     :ssl-ca-cert "test-resources/ca.pem"}]
    (spit whitelist-file whitelisted-host)
    (with-test-db
      (svc-utils/call-with-puppetdb-instance
       (-> (svc-utils/create-temp-config)
           (assoc :database *db*)
           (assoc :jetty (merge cert-config
                                {:ssl-port 0
                                 :ssl-host "0.0.0.0"
                                 :ssl-protocols "TLSv1,TLSv1.1,TLSv1.2"}))
           (assoc-in [:puppetdb :certificate-whitelist] (str whitelist-file)))
       (fn []
         (pl-http/get (str (utils/base-url->str (assoc *base-url* :version :v4))
                           "/facts")
                      (merge cert-config
                             {:headers {"accept" "application/json"}
                              :as :text})))))))

(deftest cert-whitelists
  (testing "hosts not in whitelist should be forbidden"
    (let [response (make-https-request-with-whitelisted-host "bogus")]
      (is (= 403 (:status response)))
      (is (re-find #"Permission denied" (:body response)))))

  (testing "host in the cert whitelist is allowed"
    (let [response (make-https-request-with-whitelisted-host "localhost")]
      (is (= 200 (:status response)))
      (is (not (re-find #"Permission denied" (:body response)))))))

(deftest unsupported-database-triggers-shutdown
  (svc-utils/with-single-quiet-pdb-instance
    (let [config (-> (get-service svc-utils/*server* :DefaultedConfig)
                     conf/get-config)
          expected-oldest scf-store/oldest-supported-db]
      (doseq [v [[8 1]
                 [8 2]
                 [8 3]
                 [8 4]
                 [9 0]
                 [9 1]
                 [9 2]
                 [9 3]
                 [9 4]
                 [9 5]]]
        (with-redefs [sutils/db-metadata (delay {:database nil :version v})]
          (try
            (jdbc/with-db-connection *db*
              (prep-db *db* config))
           (catch clojure.lang.ExceptionInfo e
             (let [{:keys [kind current oldest]} (ex-data e)]
               (is (= ::svcs/unsupported-database kind))
               (is (= v current))
               (is (= expected-oldest oldest)))))))
      (with-redefs [sutils/db-metadata (delay {:database nil :version [9 6]})]
        (is (do
              ;; Assumes prep-db is idempotent, which it is
              (jdbc/with-db-connection *db*
                (prep-db *db* config))
              true))))))

(deftest unsupported-database-settings-trigger-shutdown
  (svc-utils/with-single-quiet-pdb-instance
    (let [config (-> (get-service svc-utils/*server* :DefaultedConfig)
                     conf/get-config)
          settings (request-database-settings)]
      (doseq [[setting err-value] [[:standard_conforming_strings "off"]]]
        (try
         (verify-database-settings (map #(when (= (:name %) (name setting))
                                           (assoc % :setting err-value))
                                        settings))
         (catch clojure.lang.ExceptionInfo e
           (let [{:keys [kind failed-validation]} (ex-data e)]
             (is (= ::svcs/invalid-database-configuration kind))
             (is (= (get-in failed-validation [setting :actual]) err-value))))))
        (is (do
              (verify-database-settings settings)
              true)))))

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
        (migrate!)
        (dotimes [i 10]
          (let [name (str "foo-" i)]
            (scf-store/add-certname! name)
            (scf-store/deactivate-node! name deactivation-time)))
        (let [cfg (-> *server* (get-service :DefaultedConfig) conf/get-config)
              db-cfg (assoc (:database cfg) :node-purge-gc-batch-limit limit)]
          (collect-garbage db-cfg lock db-cfg
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
          gc svcs/collect-garbage]
      (with-redefs [svcs/stop-gc-wait-ms (constantly (if slow-gc?
                                                       0 ;; no point in waiting
                                                       default-timeout-ms))
                    svcs/collect-garbage (fn [& args]
                                           (deliver gc-blocked true)
                                           @gc-proceed
                                           (apply gc args))]
        (with-pdb-with-no-gc
          (let [pdb (get-service *server* :PuppetDBServer)
                db-cfg (-> *server* (get-service :DefaultedConfig) conf/get-config :database)
                stop-status (-> pdb service-context :stop-status)
                lock (ReentrantLock.)]
            (utils/noisy-future
             (svcs/coordinate-gc-with-shutdown db-cfg lock db-cfg
                                               (svcs/db-config->clean-request db-cfg)
                                               stop-status))
            @gc-blocked
            (is #{:collecting-garbage} @stop-status)
            (when-not slow-gc?
              (deliver gc-proceed true))))))
    (is (= 1 (->> @log-output
                  (logs-matching expected-msg-rx)
                  count)))))

(deftest stop-waits-a-bit-for-periodic-gc
  (test-stop-with-periodic-gc-running false #"^Periodic activities halted"))

(deftest stop-ignores-gc-that-takes-too-long
  (test-stop-with-periodic-gc-running true #"^Forcibly terminating periodic activities"))
