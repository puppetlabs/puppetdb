(ns puppetlabs.puppetdb.sync.command-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clj-http.client :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :as rt]
            [puppetlabs.puppetdb.sync.testutils :as utils
             :refer [with-puppetdb-instance index-by json-request json-response get-json]]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.sync.command :refer :all]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [compojure.core :refer [POST routes ANY GET]]
            [clojure.tools.logging :as log]
            [clj-time.coerce :refer [to-date-time]]
            [clj-time.core :as t]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.test]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sync stream comparison tests

(defn records-to-fetch' [local remote]
  (records-to-fetch report-key (constantly 0) local remote))

(deftest records-to-fetch-test
  (let [a1 {:certname "a" :hash "hash1" :receive_time 1}
        a2 {:certname "a" :hash "hash2" :receive_time 2}
        b1 {:certname "b" :hash "hash3" :receive_time 1}
        b2 {:certname "b" :hash "hash4" :receive_time 1}
        c1 {:certname "c" :hash "hash5" :receive_time 1}
        c2 {:certname "c" :hash "hash6" :receive_time 2}]
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
                  (records-to-fetch (juxt :certname :hash) (constantly 0)
                   [] (map generate-report (range ten-billion)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests for the test infrastructure

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [puppetlabs.puppetdb.cli.services/mq-endpoint ~mq-name]
     (do ~@body)))

(deftest two-instance-test
  (svcs/without-jmx
   (with-alt-mq "puppetlabs.puppetdb.commands-1"
     (let [config-1 (utils/sync-config)
           config-2 (utils/sync-config)]
       (with-puppetdb-instance config-1
         (let [report (tur/munge-example-report-for-storage (:basic reports))]
           (pdb-client/submit-command-via-http! (utils/pdb-url) "store report" 5 report)
           @(rt/block-until-results 100 (export/reports-for-node (utils/pdb-url) (:certname report)))

           (with-alt-mq "puppetlabs.puppetdb.commands-2"
             (with-puppetdb-instance config-2
               (pdb-client/submit-command-via-http! (utils/pdb-url) "store report" 5 report)
               @(rt/block-until-results 100 (export/reports-for-node (utils/pdb-url) (:certname report)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull changes tests
;;;
;;; These tests isolate PDB-Y. We store some fixture data in it, then synthesize
;;; a sync command. We serve requests back to PDB-X with a mock so we can
;;; check the right ones were made. Finally, we check that PDB-Y has the right data
;;; after sync.

(defn logging-query-handler [path requests-atom response]
  (routes (GET path {query-params :query-params}
               (when-let [query (query-params "query")]
                 (swap! requests-atom conj query)
                 (json-response response)))))

(deftest pull-reports-test
  (let [report-1 (-> reports :basic tur/munge-example-report-for-storage)
        report-2 (assoc report-1 :certname "bar.local")
        newer-report-2 (assoc report-2 :puppet_version "4.0.0")
        pdb-x-queries (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/reports" pdb-x-queries [newer-report-2])]

    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store two reports in PDB Y
      (svcs/sync-command-post (utils/pdb-url) "store report" 5 report-1)
      (svcs/sync-command-post (utils/pdb-url) "store report" 5 report-2)

      (let [created-report-1 (first (export/reports-for-node (utils/pdb-url) (:certname report-1)))
            created-report-2 (first (export/reports-for-node (utils/pdb-url) (:certname report-2)))]
        (is (= "3.0.1" (:puppet_version created-report-2)))

        ;; Send a sync command to PDB Y, where one hash is different than what's stored
        (svcs/sync-command-post (utils/pdb-url) "sync" 1
                                  {:origin_host_path (utils/stub-url-str "/pdb-x/v4")
                                   :entity :reports
                                   :sync_data [{"certname" "bar.local"
                                                "hash" "something totally different"
                                                "start_time" "2011-01-03T15:00:00Z"}
                                               {"certname" "foo.local"
                                                "hash" (:hash created-report-1)
                                                "start_time" "2011-01-01T15:00:00Z"}]})

        ;; We should see that the sync happened, and that only one report was pulled from PDB X
        (let [puppet-versions (map :puppet_version (export/reports-for-node (utils/pdb-url) (:certname report-2)))]
          (is (= #{"4.0.0" "3.0.1"} (set puppet-versions)))
          (is (= 1 (count @pdb-x-queries))))))))

(def facts {:certname "foo.local"
            :environment "DEV"
            :values tuf/base-facts
            :producer_timestamp (new java.util.Date)})

(deftest pull-factsets-test
  (let [newer-facts (assoc facts :environment "PRODUCTION")
        pdb-x-queries (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/factsets" pdb-x-queries [newer-facts])]
    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store factsets in PDB Y
      (doseq [c (map char (range (int \a) (int \f)))]
        (svcs/sync-command-post (utils/pdb-url) "replace facts" 4
                                  (assoc facts :certname (str c ".local"))))

      (let [local-factsets (index-by :certname (get-json (utils/pdb-url) "/factsets"))
            timestamps (ks/mapvals (comp to-date-time :producer_timestamp) local-factsets)]
        (is (= 5 (count local-factsets)))

        ;; Send a sync command to PDB Y
        (svcs/sync-command-post (utils/pdb-url) "sync" 1
                                  {:origin_host_path (utils/stub-url-str "/pdb-x/v4")
                                   :entity :factsets
                                   :sync_data [;; time is newer than local, hash is different -> should pull
                                               {:certname "a.local"
                                                :hash "different"
                                                :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))}
                                               ;; time is older than local, hash is different -> no pull
                                               {:certname "b.local"
                                                :hash "different"
                                                :producer_timestamp (t/minus (timestamps "b.local") (t/days 1))}
                                               ;; time is the same, hash is the same -> no pull
                                               {:certname "c.local"
                                                :hash (-> local-factsets (get "c.local") :hash)
                                                :producer_timestamp (timestamps "c.local")}
                                               ;; time is the same, hash is lexically less -> no pull
                                               {:certname "d.local"
                                                :hash "0000000000000000000000000000000000000000"
                                                :producer_timestamp (timestamps "d.local")}
                                               ;; time is the same, hash is lexically greater -> should pull
                                               {:certname "e.local"
                                                :hash "ffffffffffffffffffffffffffffffffffffffff"
                                                :producer_timestamp (timestamps "e.local")}]}))

      ;; We should see that the sync happened, and that only one factset query was made to PDB X
      (let [synced-factsets (get-json (utils/pdb-url) "/factsets")
            environments (->> synced-factsets (map :environment) (into #{}))]
        (is (= #{"DEV" "PRODUCTION"} environments))
        (is (= 2 (count @pdb-x-queries)))))))

(deftest pull-catalogs-test
  (let [catalog (get-in wire-catalogs [6 :basic])
        pdb-x-queries (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/catalogs" pdb-x-queries [(assoc catalog
                                                                                       :certname "a.local"
                                                                                       :environment "PRODUCTION")])]
    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store catalogs in PDB Y
      (doseq [c (map char (range (int \a) (int \f)))]
        (svcs/sync-command-post (utils/pdb-url) "replace catalog" 6
                                  (assoc catalog :certname (str c ".local"))))

      (let [local-catalogs (index-by :certname (get-json (utils/pdb-url) "/catalogs"))
            timestamps (ks/mapvals (comp to-date-time :producer_timestamp) local-catalogs)]
        (is (= 5 (count local-catalogs)))

        ;; Send a sync command to PDB Y
        (svcs/sync-command-post (utils/pdb-url) "sync" 1
                                  {:origin_host_path (utils/stub-url-str "/pdb-x/v4")
                                   :entity :catalogs
                                   :sync_data [;; time is newer than local, hash is different -> should pull
                                               {:certname "a.local"
                                                :hash "different"
                                                :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))}
                                               ;; time is older than local, hash is different -> no pull
                                               {:certname "b.local"
                                                :hash "different"
                                                :producer_timestamp (t/minus (timestamps "b.local") (t/days 1))}
                                               ;; time is the same, hash is the same -> no pull
                                               {:certname "c.local"
                                                :hash (-> local-catalogs (get "c.local") :hash)
                                                :producer_timestamp (timestamps "c.local")}
                                               ;; time is the same, hash is lexically less -> no pull
                                               {:certname "d.local"
                                                :hash "0000000000000000000000000000000000000000"
                                                :producer_timestamp (timestamps "d.local")}
                                               ;; time is the same, hash is lexically greater -> should pull
                                               {:certname "e.local"
                                                :hash "ffffffffffffffffffffffffffffffffffffffff"
                                                :producer_timestamp (timestamps "e.local")}]}))

      ;; We should see that the sync happened, and that only one catalogs query was made to PDB X
      (let [synced-catalogs (get-json (utils/pdb-url) "/catalogs")
            environments (->> synced-catalogs (map :environment) (into #{}))]
        (is (= #{"DEV" "PRODUCTION"} environments))
        (is (= 2 (count @pdb-x-queries)))))))

(deftest query-remote-failure
  (with-puppetdb-instance (utils/sync-config)
    (is (thrown+-with-msg? [:type :puppetlabs.puppetdb.sync.command/remote-host-error]
                           #"Error querying .*broken-url-here for catalogs with query.* status code 404.*"
                           (query-remote (str (base-url->str (utils/pdb-url)) "/broken-url-here") :catalogs ["=" "hash" "1234"])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Trigger sync tests
;;;
;;; These tests isolate PDB X. It is first started and populated with
;;; fixture data.  Then a sync is triggered, with the destination URL
;;; "/pdb-y/v4" standing in place of PDB Y. This endpoint is made
;;; available in the with-puppetdb-instance test helper function, and
;;; all requests to it end up in the let bound @requests-atom. We can
;;; then use that to check that the appropriate sync command was sent.
(defn trigger-sync [remote-host-url-str]
  (http/post (utils/trigger-sync-url-str)
             (json-request {:remote_host_path remote-host-url-str})))

(defn logging-command-handler [path requests-atom]
  (routes
   (POST path {:as req}
         (swap! requests-atom conj (update-in req [:body] slurp))
         {:status 200 :body "Success"})))

(deftest test-push-report
  (let [requests-atom (atom [])]
   (with-puppetdb-instance (utils/sync-config (logging-command-handler "/pdb-y/v4" requests-atom))
     (let [report (tur/munge-example-report-for-storage (:basic reports))]
       (svcs/sync-command-post (utils/pdb-url) "store report" 5 report)
       (trigger-sync (utils/stub-url-str "/pdb-y/v4"))

       (is (= {:command "sync",
               :version 1,
               :payload {:origin_host_path (utils/pdb-url-str)
                         :entity "reports"
                         :sync_data [{:certname "foo.local"
                                      :hash "fe522613ab895955370f1c2b7b5d8b53c0e53ba5"
                                      :start_time "2011-01-01T15:00:00Z"}]}}
              (-> @requests-atom first :body (json/parse-string true))))))))

(deftest test-push-factset
  (let [requests-atom (atom [])]
    (with-puppetdb-instance (utils/sync-config (logging-command-handler "/pdb-y/v4" requests-atom))
     (svcs/sync-command-post (utils/pdb-url) "replace facts" 4 facts)
     (trigger-sync (utils/stub-url-str "/pdb-y/v4"))

     (let [factset-sync-request (->> @requests-atom
                                     (map :body)
                                     (map #(json/parse-string % true))
                                     (filter #(= (:command %) "sync"))
                                     (filter #(= (get-in % [:payload :entity]) "factsets"))
                                     first)
           actual-timestamp (get-in factset-sync-request [:payload :sync_data 0 :producer_timestamp])]
       (is (= {:command "sync",
               :version 1,
               :payload {:origin_host_path (utils/pdb-url-str)
                         :entity "factsets"
                         :sync_data [{:certname "foo.local"
                                      :hash "d280ba770e32b852588711b87e141605c3d14cb6"
                                      :producer_timestamp actual-timestamp}]}}
              factset-sync-request))))))

(deftest test-push-catalog
  (let [catalog (get-in wire-catalogs [6 :basic])
        requests-atom (atom [])]
    (with-puppetdb-instance (utils/sync-config (logging-command-handler "/pdb-y/v4" requests-atom))
     (svcs/sync-command-post (utils/pdb-url) "replace catalog" 6 catalog)
     (trigger-sync (utils/stub-url-str "/pdb-y/v4"))

     (let [catalog-sync-request (->> @requests-atom
                                     (map :body)
                                     (map #(json/parse-string % true))
                                     (filter #(= (:command %) "sync"))
                                     (filter #(= (get-in % [:payload :entity]) "catalogs"))
                                     first)
           actual-timestamp (get-in catalog-sync-request [:payload :sync_data 0 :producer_timestamp])]
       (is (= {:command "sync",
               :version 1,
               :payload {:origin_host_path (utils/pdb-url-str)
                         :entity "catalogs"
                         :sync_data [{:certname "basic.wire-catalogs.com"
                                      :hash "6b7ed1a8dfd0fd0575537f22d6ede9833f87ef65"
                                      :producer_timestamp "2014-07-10T22:33:54Z"}]}}
              catalog-sync-request))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end tests

(defn- with-n-pdbs
  ([n f] (svcs/without-jmx
          (with-n-pdbs n f [])))
  ([n f pdb-infos]
   (if (= n (count pdb-infos))
     (apply f pdb-infos)
     (let [config (utils/sync-config)
           mq-name (str "puppetlabs.puppetdb.commands-" (inc (count pdb-infos)))]
       (with-alt-mq mq-name
         (with-puppetdb-instance config
           (with-n-pdbs n f
             (conj pdb-infos {:mq-name mq-name
                              :config config
                              :service-url (assoc svcs/*base-url* :prefix "/pdb" :version :v4)
                              :sync-url    (assoc svcs/*base-url* :prefix "/sync" :version :v1)}))))))))

(deftest end-to-end-report-replication
  (with-n-pdbs 2
    (fn [pdb1 pdb2]
      (let [report (tur/munge-example-report-for-storage (:basic reports))]
        (with-alt-mq (:mq-name pdb1)
          (pdb-client/submit-command-via-http! (:service-url pdb1) "store report" 5 report)
          @(rt/block-until-results 100 (export/reports-for-node (:service-url pdb1) (:certname report))))

        (with-alt-mq (:mq-name pdb2)
          ;; pdb1 -> pdb2 "here's my hashes, pull from me what you need"
          (http/post (str (base-url->str (:sync-url pdb1)) "/trigger-sync")
                     {:headers {"content-type" "application/json"}
                      :body (json/generate-string {:remote_host_path (str (base-url->str (:service-url pdb2)) "/commands") })
                      :throw-entire-message true})

          ;; now pdb2 receives the message, from pdb1, and works on queue #2
          @(rt/block-until-results 200 (export/reports-for-node (:service-url pdb2) (:certname report)))

          (is (= (export/reports-for-node (:service-url pdb1) (:certname report))
                 (export/reports-for-node (:service-url pdb2) (:certname report)))))))))
