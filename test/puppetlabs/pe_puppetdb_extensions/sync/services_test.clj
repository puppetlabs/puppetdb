(ns puppetlabs.pe-puppetdb-extensions.sync.services-test
  (:import [org.joda.time Period]
           [java.net URI])
  (:require [puppetlabs.pe-puppetdb-extensions.sync.services :refer :all]
            [puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary :as bucketed-summary]
            [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clj-time.core :refer [seconds]]
            [puppetlabs.puppetdb.time :refer [period? periods-equal? parse-period]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.examples.reports :as report-examples]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-ext-instances blocking-command-post]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-time.coerce :refer [to-date-time]]))

(deftest enable-periodic-sync?-test
  (testing "Happy case"
    (is (= true (enable-periodic-sync?
                 [{:server-url (URI. "http://foo.bar:8080"), :interval (-> 12 seconds)}]))))

  (testing "Disable sync cases"
    (are [remote-config] (= false (enable-periodic-sync? remote-config))
      [{:server-url (URI. "http://foo.bar:8080"), :interval (-> 0 seconds)}]
      [{:server-url (URI. "http://foo.bar:8080")}]
      []
      nil))

  (testing "Invalid sync configs"
    (are [remote-config] (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                                   (enable-periodic-sync? remote-config))

      [{:server_url "http://foo.bar:8080", :interval (-> -12 seconds)}])))

(deftest validate-trigger-sync-test
  (let [allow-unsafe-sync-triggers false
        jetty-config {}
        remotes-config [{:server-url (URI. "http://foo.bar:8080"), :interval (parse-period "120s")}]]
    (is (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config
                               {:url "http://foo.bar:8080/pdb/query/v4"}))
    (is (not (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config
                                    {:url "http://baz.buzz:8080/pdb/query/v4"})))))

(deftest test-wait-for-sync
  (testing "Happy path of processing commands"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (async/>!! processed-commands-chan {:id cmd-1})
      (is (= :done (async/<!! finished-sync)))))

  (testing "Receiving a processed command before submitted commands channel is closed"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/>!! processed-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (is (= :done (async/<!! finished-sync)))))

  (testing "timeout result when processing of commands is too slow"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 500)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (is (= :timed-out (async/<!! finished-sync)))))

  (testing "system shutting down during initial sync"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! processed-commands-chan)
      (is (= :shutting-down (async/<!! finished-sync))))))

(deftest test-reports-summary-query
  (testing "no reports"
    (with-ext-instances [pdb (utils/sync-config nil)]
      (is (= {} (svcs/get-json (utils/sync-url) "/reports-summary")))))

  (testing "two reports"
    (with-ext-instances [pdb (utils/sync-config nil)]
      (let [report (assoc (:basic report-examples/reports)
                          :producer_timestamp "2014-01-01T08:05:00.000Z")
            report2 (assoc (:basic2 report-examples/reports)
                           :producer_timestamp "2014-01-01T09:01:00.000Z")]
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report))
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report2)))
      (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
            expected {"2014-01-01T08:00:00.000Z" "ff9fd7a2c2459280c30632d3390345b5"
                      "2014-01-01T09:00:00.000Z" "ee94b04f27d6f844bcac35cffe841d93"}]
        (is (= expected actual)))))

  (testing "caching"
    (with-ext-instances [pdb (utils/sync-config nil)]
      (let [report (assoc (:basic report-examples/reports)
                          :producer_timestamp "2014-01-01T08:05:00.000Z")]
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report)))
      (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
            expected {"2014-01-01T08:00:00.000Z" "ff9fd7a2c2459280c30632d3390345b5"}]
        (is (= expected actual)))

      ;; With no changes, the second request should issue a query which omits
      ;; the hour we just saw
      (let [queried-timespans (atom nil)
            real-generate-bucketed-summary-query bucketed-summary/generate-bucketed-summary-query]
        (with-redefs [bucketed-summary/generate-bucketed-summary-query
                      (fn [table timespans]
                        (reset! queried-timespans timespans)
                        (real-generate-bucketed-summary-query table timespans))]
          (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
                expected {"2014-01-01T08:00:00.000Z" "ff9fd7a2c2459280c30632d3390345b5"}]
            (is (= expected actual))
            (is (= [[:open (to-date-time "2014-01-01T08:00:00.000Z")]
                    [(to-date-time "2014-01-01T09:00:00.000Z") :open]]
                   @queried-timespans)))))))

  (testing "cache invalidation"
    (with-ext-instances [pdb (utils/sync-config nil)]
      (let [report (assoc (:basic report-examples/reports)
                          :producer_timestamp "2014-01-01T08:05:00.000Z")
            report2 (assoc (:basic2 report-examples/reports)
                           :producer_timestamp "2014-01-01T08:10:00.000Z")]
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report))
        (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
              expected {"2014-01-01T08:00:00.000Z" "ff9fd7a2c2459280c30632d3390345b5"}]
          (is (= expected actual)))

        ;; the second report is in the same bucket as the first, so submitting
        ;; this command should invalidate the cache and give us a new result
        ;; when running the summary
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report2))
        (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
              expected {"2014-01-01T08:00:00.000Z" "afd22efd338d2c1802b62f1fb67beeb8"}]
          (is (= expected actual)))))))

(deftest remote-url->server-url-test
  []
  (testing "remote-url->server-url"
    (is (= (remote-url->server-url "http://localhost:8080/pdb/query/v4")
           "http://localhost:8080"))
    (is (= (remote-url->server-url "https://localhost:8080/foo/pdb/query/v4")
           "https://localhost:8080/foo"))
    (is (= (remote-url->server-url "https://localhost:8080/foo/bar/pdb/query/v4")
           "https://localhost:8080/foo/bar"))))
