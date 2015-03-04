(ns puppetlabs.puppetdb-sync.command-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb-sync.testutils :as utils]
            [clj-http.client :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.jetty :as jutils]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :as rt]
            [puppetlabs.puppetdb-sync.testutils :refer [requests request-catcher-service with-puppetdb-instance CannedResponse expect-response request-catcher-fixture]]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb-sync.command :as command]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.client :as pdb-client]))

(use-fixtures :each request-catcher-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sync stream comparison tests

(defn generate-report
  [x]
  {:hash (random-string 10) :certname (random-string 10) :producer-timestamp (rand)})

(def orderings
  (let [a1 {:certname "a" :hash "hash1" :receive_time 1}
        a2 {:certname "a" :hash "hash2" :receive_time 2}
        b1 {:certname "b" :hash "hash3" :receive_time 1}
        b2 {:certname "b" :hash "hash4" :receive_time 1}
        c1 {:certname "c" :hash "hash5" :receive_time 1}
        c2 {:certname "c" :hash "hash6" :receive_time 2}]
    [{:local [a2 b1 b2 c1 c2] ;; local missing a report
      :remote [a1 a2 b1 b2 c1 c2]
      :record ["hash1"]}
     {:local [a1 a2 c1 c2] ;; local missing a certname/corresponding reports
      :remote [a1 a2 b1 b2 c1 c2]
      :record ["hash3" "hash4"]}
     {:local [a1 a2 b1 b2 c1] ;; remote missing a certname/corresponding reports
      :remote [a1 a2 c1 c2]
      :record ["hash6"]}
     {:local [a1 a2 b1 c2] ;; local missing a report for two certnames
      :remote [a1 a2 b1 b2 c1 c2]
      :record ["hash4" "hash5"]}
     {:local [a1 a2 b1 c2] ;; remote empty
      :remote []
      :record []}
     {:local [a1 a2 b1 b2] ;; local missing reports at end of list
      :remote [a1 a2 b1 b2 c1 c2]
      :record ["hash5" "hash6"]}
     {:local [c1] ;; local missing reports at beginning of list
      :remote [a1 a2 b1 b2 c1]
      :record ["hash1" "hash2" "hash3" "hash4"]}
     {:local [] ;; local empty
      :remote [a1 a2 b1 b2 c1 c2]
      :record ["hash1" "hash2" "hash3" "hash4" "hash5" "hash6"]}]))

(deftest command-test
  (doseq [order orderings]
    (let [record (atom [])
          test-fn (fn [x] (swap! record conj (:hash x)))]
      (doseq [x (command/compare-streams (:local order) (:remote order))]
        (test-fn x))
      (is (= (set (:record order)) (set @record))))))

(deftest laziness-of-comparison-seq
  (let [ten-billion 10000000000]
    (is (= 10
           (count
            (take 10
                  (command/compare-streams
                   [] (map generate-report (range ten-billion)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests for the test infrastructure

(defn canned-url []
  (assoc jutils/*base-url* :prefix "/canned-response" :version :v4))

(defn pdb-url []
  (assoc jutils/*base-url* :prefix "/pdb" :version :v4))

(deftest canned-response-test
  (utils/with-puppetdb-instance (utils/sync-config)
    (let [response-service (get-service jutils/*server* :CannedResponse)]
      (expect-response response-service "/canned-response/v4/foo" {:status 200 :body "all good"})
      (is (= "all good" (:body (http/get (str (base-url->str (canned-url)) "/foo"))))))))

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [puppetlabs.puppetdb.cli.services/mq-endpoint ~mq-name]
     (do ~@body)))

(deftest two-instance-test
  (jutils/without-jmx
   (with-alt-mq "puppetlabs.puppetdb.commands-1"
     (let [config-1 (utils/sync-config)
           config-2 (utils/sync-config)
           port-1 (get-in config-1 [:jetty :port])
           port-2 (get-in config-2 [:jetty :port])]
       (with-puppetdb-instance config-1
         (let [report (tur/munge-example-report-for-storage (:basic reports))]
           (pdb-client/submit-command-via-http! (assoc jutils/*base-url* :prefix "/pdb") "store report" 5 report)
           @(rt/block-until-results 100 (export/reports-for-node (assoc jutils/*base-url* :prefix "/pdb") (:certname report)))

           (with-alt-mq "puppetlabs.puppetdb.commands-2"
             (with-puppetdb-instance config-2
               (pdb-client/submit-command-via-http! (assoc jutils/*base-url* :prefix "/pdb") "store report" 5 report)
               @(rt/block-until-results 100 (export/reports-for-node (assoc jutils/*base-url* :prefix "/pdb") (:certname report)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull from remote instance tests

(deftest round-trip-test
  (utils/with-puppetdb-instance (utils/sync-config)
    (let [response-service (get-service jutils/*server* :CannedResponse)
          report-2 (-> reports
                       :basic
                       (assoc :certname "bar.local")
                       tur/munge-example-report-for-storage)]
      (expect-response response-service "/canned-response/v4/reports" {:status 200
                                                                       :body (json/generate-string [(assoc report-2 :puppet_version "4.0.0")])})
      (let [report-1 (tur/munge-example-report-for-storage (:basic reports))]
        (jutils/sync-command-post (pdb-url) "store report" 5 report-1)
        (jutils/sync-command-post (pdb-url) "store report" 5 report-2)
        (is (= ["3.0.1"] (map :puppet_version (export/reports-for-node jutils/*base-url* (:certname report-2)))))
        (jutils/sync-command-post (pdb-url) "sync" 1 {:origin_host_path (base-url->str (canned-url))
                                                      :entity_type :reports
                                                      :sync_data [{"certname" "bar.local"
                                                                   "hash" "something totally different"
                                                                   "start_time" "2011-01-03T15:00:00Z"}
                                                                  {"certname" "foo.local"
                                                                   "hash" "d694c8e38f8efec340a543a55b4448001dfc4184"
                                                                   "start_time" "2011-01-01T15:00:00Z"}]})

        (let [puppet-versions (map :puppet_version (export/reports-for-node jutils/*base-url* (:certname report-2)))]
          (is (= 2 (count puppet-versions)))
          (is (= #{"4.0.0" "3.0.1"} (set puppet-versions))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Push to remote instance tests

(deftest test-push
  (with-puppetdb-instance (utils/sync-config)
    (let [report (tur/munge-example-report-for-storage (:basic reports))]
      (jutils/sync-command-post (pdb-url) "store report" 5 report)
      (http/post (str (base-url->str (assoc jutils/*base-url*
                                       :prefix "/sync"
                                       :version :v1)) "/trigger-sync")
                 {:headers {"content-type" "application/json"}
                  :throw-exceptions false
                  :body (json/generate-string {:remote_host_path (str (base-url->str (assoc jutils/*base-url* :prefix "/request-catcher" :version :v1)) "/consume-request")})})
      (is (= {"command" "sync",
              "version" 1,
              "payload" {"origin_host_path" (base-url->str (assoc jutils/*base-url* :prefix "/pdb"))
                         "entity_type" "reports"
                         "sync_data" [{"certname" "foo.local" "hash" "fe522613ab895955370f1c2b7b5d8b53c0e53ba5", "start_time" "2011-01-01T15:00:00Z"}]}}
             (json/parse-string (:body (first @requests))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end tests

(defn- with-n-pdbs
  ([n f] (jutils/without-jmx
          (with-n-pdbs n f [])))
  ([n f pdb-infos]
   (if (= n (count pdb-infos))
     (apply f pdb-infos)
     (let [config (utils/sync-config)]
       (with-alt-mq (str "puppetlabs.puppetdb.commands-" (inc (count pdb-infos)))
         (with-puppetdb-instance config
           (with-n-pdbs n f
             (let [port (get-in config [:jetty :port])
                   base-url (assoc jutils/*base-url* :port port)]
               (conj pdb-infos {:config config
                                :port port
                                :base-url base-url
                                :service-url (assoc base-url :prefix "/pdb")
                                :sync-url    (assoc base-url :prefix "/sync" :version :v1)})))))))))

(deftest end-to-end-report-replication
  (with-n-pdbs 2
    (fn [pdb1 pdb2]
      (let [report (tur/munge-example-report-for-storage (:basic reports))]

        (with-alt-mq "puppetlabs.puppetdb.commands-1"
          (pdb-client/submit-command-via-http! (:service-url pdb1) "store report" 5 report)
          @(rt/block-until-results 100 (export/reports-for-node (:service-url pdb1) (:certname report))))

        (with-alt-mq "puppetlabs.puppetdb.commands-2"
          ;; pdb1 -> pdb2 "here's my hashes, pull from me what you need"
          (http/post (str (base-url->str (:sync-url pdb1)) "/trigger-sync")
                     {:headers {"content-type" "application/json"}
                      :body (json/generate-string {:remote_host_path (str (base-url->str (:service-url pdb2)) "/commands") })
                      :throw-entire-message true})

          ;; now pdb2 receives the message, from pdb1, and works on queue #2
          @(rt/block-until-results 200 (export/reports-for-node (:service-url pdb2) (:certname report)))

          (is (= (export/reports-for-node (:service-url pdb1) (:certname report))
                 (export/reports-for-node (:service-url pdb2) (:certname report)))))))))

