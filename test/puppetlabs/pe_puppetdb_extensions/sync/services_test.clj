(ns puppetlabs.pe-puppetdb-extensions.sync.services-test
  (:import [org.joda.time Period]
           [java.net URI])
  (:require [puppetlabs.pe-puppetdb-extensions.sync.services :refer :all]
            [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clj-time.core :refer [seconds]]
            [puppetlabs.puppetdb.time :refer [period? periods-equal? parse-period]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.examples.reports :as report-examples]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance blocking-command-post]]
            [puppetlabs.puppetdb.testutils.services :as svcs]))

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
    (is (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config {:url "http://foo.bar:8080/pdb/query/v4"}))
    (is (not (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config {:url "http://baz.buzz:8080/pdb/query/v4"})))))

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
    (with-puppetdb-instance (utils/pdb1-sync-config)
      (is (= {} (svcs/get-json (utils/sync-url) "/reports-summary")))))

  (testing "two reports"
   (with-puppetdb-instance (utils/pdb1-sync-config)
     (let [report (assoc (:basic report-examples/reports)
                         :producer_timestamp "2014-01-01T08:05:00.000Z")
           report2 (assoc (:basic2 report-examples/reports)
                          :producer_timestamp "2014-01-01T09:01:00.000Z")]
       (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report))
       (blocking-command-post (utils/pdb-cmd-url) "store report" 5 (reports/report-query->wire-v6 report2))
       (let [actual (json/parse-string (svcs/get-url (utils/sync-url) "/reports-summary"))
             expected {"2014-01-01T08:00:00.000Z" "ff9fd7a2c2459280c30632d3390345b5"
                       "2014-01-01T09:00:00.000Z" "ee94b04f27d6f844bcac35cffe841d93"}]
         (is (= expected actual)))))))
