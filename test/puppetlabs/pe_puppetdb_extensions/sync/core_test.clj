(ns puppetlabs.pe-puppetdb-extensions.sync.core-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :refer :all :exclude [report]]
            [clj-time.core :as t]
            [puppetlabs.pe-puppetdb-extensions.sync.core :as sync-core]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-puppetdb-instance]]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb.testutils :refer [with-alt-mq without-jmx]]
            [puppetlabs.puppetdb.testutils.db :refer [call-with-test-dbs]]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]))

;;; Sync stream comparison tests

(defn records-to-fetch' [local remote]
  (sync-core/records-to-fetch sync-core/report-key (constantly 0) local remote (t/now) (parse-period "0s")))

(deftest records-to-fetch-test
  (let [a1 {:certname "a" :hash "hash1" :producer_timestamp 1}
        a2 {:certname "a" :hash "hash2" :producer_timestamp 2}
        b1 {:certname "b" :hash "hash3" :producer_timestamp 1}
        b2 {:certname "b" :hash "hash4" :producer_timestamp 1}
        c1 {:certname "c" :hash "hash5" :producer_timestamp 1}
        c2 {:certname "c" :hash "hash6" :producer_timestamp 2}]
    (are [local remote result] (= result
                                  (set (map :hash (records-to-fetch' local remote))))

         [a2 b1 b2 c1 c2] ;; local missing a report
         [a1 a2 b1 b2 c1 c2]
         #{"hash1"}

         [a1 a2 c1 c2] ;; local missing a certname/corresponding reports
         [a1 a2 b1 b2 c1 c2]
         #{"hash3" "hash4"}

         [a1 a2 b1 b2 c1] ;; remote missing a certname/corresponding reports
         [a1 a2 c1 c2]
         #{"hash6"}

         [a1 a2 b1 c2] ;; local missing a report for two certnames
         [a1 a2 b1 b2 c1 c2]
         #{"hash4" "hash5"}

         [a1 a2 b1 c2] ;; remote empty
         []
         #{}

         [a1 a2 b1 b2] ;; local missing reports at end of list
         [a1 a2 b1 b2 c1 c2]
         #{"hash5" "hash6"}

         [c1] ;; local missing reports at beginning of list
         [a1 a2 b1 b2 c1]
         #{"hash1" "hash2" "hash3" "hash4"}

         [] ;; local empty
         [a1 a2 b1 b2 c1 c2]
         #{"hash1" "hash2" "hash3" "hash4" "hash5" "hash6"})))

(defn generate-report
  [x]
  {:hash (random-string 10) :certname (random-string 10) :producer-timestamp (rand)})

(deftest laziness-of-records-to-fetch
  (let [ten-billion 10000000000]
    (is (= 10
           (count
            (take 10
                  (sync-core/records-to-fetch (juxt :certname :hash) (constantly 0)
                                              [] (map generate-report (range ten-billion))
                                              (t/now)
                                              (parse-period "0s"))))))))


;;; Tests for the test infrastructure

(deftest two-instance-test
  (call-with-test-dbs 2
    (fn [db1 db2]
      (without-jmx
       (with-alt-mq "puppetlabs.puppetdb.commands-1"
         (let [config-1 (assoc (utils/sync-config nil) :database db1)
               config-2 (assoc (utils/sync-config nil) :database db2)]
           (with-log-suppressed-unless-notable notable-pdb-event?
             (with-puppetdb-instance config-1
               (let [report (reports/report-query->wire-v6 (:basic reports))
                     query-fn (partial cli-svcs/query
                                       (get-service svcs/*server*
                                                    :PuppetDBServer))]
                 (blocking-command-post (utils/pdb-cmd-url)
                                        "store report" 6 report)
                 (is (not (empty? (svcs/get-reports (utils/pdb-query-url)
                                                    (:certname report)))))
                 (with-alt-mq "puppetlabs.puppetdb.commands-2"
                   (with-puppetdb-instance config-2
                     (blocking-command-post (utils/pdb-cmd-url)
                                            "store report" 6 report)
                     (is (not (empty? (svcs/get-reports
                                       (utils/pdb-query-url)
                                       (:certname report))))))))))))))))
