(ns puppetlabs.pe-puppetdb-extensions.state-overview-test
  (:require [clojure.test :refer :all]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]))

(deftest query-state-overview
  (with-ext-instances [pdb (utils/sync-config nil)]
    (let [report (:basic reports)
          report2 (-> (:basic2 reports)
                      (merge {:certname "bar.local" :end_time (now)}))]
      (blocking-command-post (utils/pdb-cmd-url) (:certname report)
                             "store report" command/latest-report-version
                             (reports/report-query->wire-v8 report))
      (blocking-command-post (utils/pdb-cmd-url) (:certname report2)
                             "store report" command/latest-report-version
                             (reports/report-query->wire-v8 report2))
      (testing "query with no parameters returns correct counts"
        (let [actual (svcs/get-json (utils/pe-pdb-url) "/state-overview")
              expected {:unchanged 0
                        :failed 0
                        :noop 1
                        :unresponsive 1
                        :changed 0
                        :unreported 0}]
          (is (= expected actual))))
      (testing "query with unresponsive_threshold correctly changes counts"
        (do
          ;; Wait a little while so we can test unresponsive threshold
          (Thread/sleep 1000)
          (let [actual (svcs/get-json (utils/pe-pdb-url)
                                      "/state-overview?unresponsive_threshold=1")
                ;; Wait a little while so we can test unresponsive threshold
                expected {:unchanged 0
                          :failed 0
                          :noop 0
                          :unresponsive 2
                          :changed 0
                          :unreported 0}]
            (is (= expected actual)))))

      (testing "invalid url returns 404"
        (let [status (-> (utils/pe-pdb-url)
                         (utils/get-response "/fbar" {:throw-exceptions false})
                         :status)]
          (is (= 404 status))))

      (testing "state-overview excludes deactivated nodes"
        (do
          (blocking-command-post (utils/pdb-cmd-url) "bar.local"
                                 "deactivate node" 3 {"certname" "bar.local"})
          ;; Sleep to allow status to change to unresponsive with threshold=1
          (Thread/sleep 1000)
          (let [actual (svcs/get-json (utils/pe-pdb-url)
                                      "/state-overview?unresponsive_threshold=1")
                expected {:unchanged 0
                          :failed 0
                          :noop 0
                          :unresponsive 1
                          :changed 0
                          :unreported 0}]
            (is (= expected actual))))))))
