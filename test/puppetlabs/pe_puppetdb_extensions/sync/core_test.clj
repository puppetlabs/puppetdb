(ns puppetlabs.pe-puppetdb-extensions.sync.core-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :exclude [report] :refer :all]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.pe-puppetdb-extensions.semlog :as semlog]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as services]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.testutils :refer [=-after? without-jmx
                                                   block-until-results]]
            [schema.core :as s]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-level with-logging-to-atom
                     with-log-suppressed-unless-notable notable-pdb-event?]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer :all :as syncc]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance index-by json-request
                     json-response get-json blocking-command-post]]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.trapperkeeper.app :refer [get-service app-context]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.puppetdb.client :as pdb-client]
            [compojure.core :refer [POST routes ANY GET context]]
            [clojure.tools.logging :as log]
            [clj-time.coerce :refer [to-date-time]]
            [clj-time.core :as t]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.slingshot :refer [try+ throw+]]
            [slingshot.test]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.pe-puppetdb-extensions.sync.events :as events]
            [puppetlabs.pe-puppetdb-extensions.sync.test-protocols :as sync-test-protos :refer [called?]]
            [puppetlabs.http.client.sync :as http])
  (:import
   [org.apache.activemq.command ActiveMQDestination]
   [org.slf4j Logger]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sync stream comparison tests

(defn records-to-fetch' [local remote]
  (records-to-fetch report-key (constantly 0) local remote (t/now) (parse-period "0s")))

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
                  (records-to-fetch (juxt :certname :hash) (constantly 0)
                                    [] (map generate-report (range ten-billion))
                                    (t/now)
                                    (parse-period "0s"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests for the test infrastructure

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [puppetlabs.puppetdb.cli.services/mq-endpoint ~mq-name]
     (do ~@body)))

(deftest two-instance-test
  (without-jmx
   (with-alt-mq "puppetlabs.puppetdb.commands-1"
     (let [config-1 (utils/sync-config)
           config-2 (utils/sync-config)]
       (with-log-suppressed-unless-notable notable-pdb-event?
        (with-puppetdb-instance config-1
          (let [report (tur/munge-example-report-for-storage (:basic reports))
                query-fn (partial cli-svcs/query (tk-app/get-service svcs/*server* :PuppetDBServer))]
            (pdb-client/submit-command-via-http! (utils/pdb-cmd-url) "store report" 5 report)
            @(block-until-results 100 (export/reports-for-node query-fn (:certname report)))

            (with-alt-mq "puppetlabs.puppetdb.commands-2"
              (with-puppetdb-instance config-2
                (pdb-client/submit-command-via-http! (utils/pdb-cmd-url) "store report" 5 report)
                @(block-until-results 100 (export/reports-for-node query-fn (:certname report))))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Test data

(def facts {:certname "foo.local"
            :environment "DEV"
            :values tuf/base-facts
            :producer_timestamp (new java.util.Date)})

(def catalog (assoc (get-in wire-catalogs [6 :basic])
                    :certname "foo.local"))

(def report (-> reports :basic tur/munge-example-report-for-storage))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Pull changes tests
;;;
;;; These tests isolate PDB-Y. We store some fixture data in it, then synthesize
;;; a sync command. We serve requests back to PDB-X with a mock so we can
;;; check the right ones were made. Finally, we check that PDB-Y has the right data
;;; after sync.

(defn logging-query-handler
  "Build a handler to stub certain query results. This can handle two kinds of
  queries; first, an 'extract' query, which it assumes to be the summary
  query. Second, a lookup-by-identity query, where the record key is
  `record-identity-key`. (this is either :hash or :certname). The responses are
  generated using the contents of stub-data-atom, which is a seq of summary
  records. Each summary record also has a :content field which is stripped out
  for the summary query and is used for the response of the lookup-by-hash
  query.

  All queries are logged to `requests-atom`."
  [path requests-atom stub-data-atom record-identity-key]
  (routes (GET path {query-params :query-params}
               (let [stub-data @stub-data-atom
                     stub-data-index (index-by record-identity-key stub-data)
                     summary-data (map #(select-keys % [:certname :hash :producer_timestamp]) stub-data)]
                 (when-let [query (vec (json/parse-string (query-params "query")))]
                   (swap! requests-atom conj query)
                   (cond
                     (= "extract" (first query))
                     (json-response summary-data)

                     (and (= "and") (first query)
                          (= ["=" (name record-identity-key)] (take 2 (second query))))
                     (let [[_ [_ _ record-hash]] query]
                       (json-response [(get stub-data-index record-hash)]))))))

          ;; fallback routes, for data that wasn't explicitly stubbed
          (context "/pdb-x/v4" []
                   (GET "/reports" [] (json-response []))
                   (GET "/factsets" [] (json-response []))
                   (GET "/catalogs" [] (json-response []))
                   (GET "/nodes" [] (json-response [])))))

(defn trigger-sync [source-pdb-url dest-sync-url]
 (http/post dest-sync-url
            {:headers {"content-type" "application/json"}
             :body (json/generate-string {:remote_host_path source-pdb-url})
             :as :text}))

(defn perform-sync [source-pdb-url dest-sync-url]
  (svcs/until-consumed #(trigger-sync source-pdb-url dest-sync-url)))

(defn perform-overlapping-sync [source-pdb-url dest-sync-url]
  (let [stop-here (promise)]
    (with-redefs
      [syncc/sync-from-remote! (fn [& args] @stop-here)]
      ;; the atom in sync-with! will cause the first of these calls to block,
      ;; and the second call to release the block.
      (let [block-first #(let [res (trigger-sync source-pdb-url dest-sync-url)]
                           (deliver stop-here nil)
                           res)
            a (future (block-first))
            b (future (block-first))]
        [@a @b]))))

(defn get-first-report [pdb-url certname]
  (first (get-json pdb-url (str "/reports/?query=" (json/generate-string [:= :certname certname])))))

(deftest pull-reports-test
  (let [report-1 (-> reports :basic tur/munge-example-report-for-storage)
        report-2 (assoc report-1 :certname "bar.local")
        pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/reports" pdb-x-queries stub-data-atom :hash)]
    (with-log-suppressed-unless-notable notable-pdb-event?
      (with-puppetdb-instance (utils/sync-config stub-handler)
        ;; store two reports in PDB Y
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 report-1)
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 report-2)

        (let [query-fn (partial cli-svcs/query (tk-app/get-service svcs/*server* :PuppetDBServer))
              created-report-1 (get-first-report (utils/pdb-query-url) (:certname report-1))
              created-report-2 (get-first-report (utils/pdb-query-url) (:certname report-2))]
          (is (= "3.0.1" (:puppet_version created-report-2)))

          ;; Set up pdb-x as a stub where 1 report has a different hash
          (reset! stub-data-atom [(assoc report-2
                                         :hash "something totally different"
                                         :producer_timestamp "2011-01-01T12:11:00-03:30"
                                         :puppet_version "4.0.0")
                                  (assoc report-1
                                         :certname "foo.local"
                                         :hash (:hash created-report-1)
                                         :start_time "2011-01-01T15:00:00Z")])

          ;; Pull data from pdb-x to pdb-y
          (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str))
          @(block-until-results 100 (export/reports-for-node query-fn (:certname report)))

          ;; We should see that the sync happened, and that only one report was pulled from PDB X
          (let [puppet-versions (map (comp :puppet_version #(json/parse-string % true) :contents)
                                     (export/reports-for-node query-fn (:certname report-2)))]
            (is (= #{"4.0.0" "3.0.1"} (set puppet-versions)))
            (is (= 2 (count @pdb-x-queries)))))))))

(deftest pull-factsets-test
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/factsets" pdb-x-queries stub-data-atom :certname)]
    (with-log-suppressed-unless-notable notable-pdb-event?
     (with-puppetdb-instance (utils/sync-config stub-handler)
       ;; store factsets in PDB Y
       (doseq [c (map char (range (int \a) (int \g)))]
         (blocking-command-post (utils/pdb-cmd-url) "replace facts" 4
                                (assoc facts :certname (str c ".local"))))

       (let [local-factsets (index-by :certname (get-json (utils/pdb-query-url) "/factsets"))
             timestamps (ks/mapvals (comp to-date-time :producer_timestamp) local-factsets)]
         (is (= 6 (count local-factsets)))

         (reset! stub-data-atom
                 [;; time is newer than local, hash is different -> should pull
                  (assoc facts
                         :certname "a.local"
                         :hash "a_different"
                         :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))
                         :environment "A")
                  ;; time is older than local, hash is different -> no pull
                  (assoc facts
                         :certname "b.local"
                         :hash "b_different"
                         :producer_timestamp (t/minus (timestamps "b.local") (t/days 1)))
                  ;; time is the same, hash is the same -> no pull
                  (assoc facts
                         :certname "c.local"
                         :hash (-> local-factsets (get "c.local") :hash)
                         :producer_timestamp (timestamps "c.local"))
                  ;; time is the same, hash is lexically less -> no pull
                  (assoc facts
                         :certname "d.local"
                         :hash "0000000000000000000000000000000000000000"
                         :producer_timestamp (timestamps "d.local"))
                  ;; time is the same, hash is lexically greater -> should pull
                  (assoc facts
                         :certname "e.local"
                         :hash "ffffffffffffffffffffffffffffffffffffffff"
                         :producer_timestamp (timestamps "e.local")
                         :environment "E")
                  ;; time is newer than local, hash is the same -> should pull
                  (assoc facts
                         :certname "f.local"
                         :hash (-> local-factsets (get "f.local") :hash)
                         :producer_timestamp (t/plus (timestamps "f.local") (t/days 1))
                         :environment "F")])

         ;; Send a sync command to PDB Y
         (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str)))

       ;; We should see that the sync happened, and that one summary query and two factset querys where made to PDB X
       (let [synced-factsets (get-json (utils/pdb-query-url) "/factsets")
             environments (->> synced-factsets (map :environment) (into #{}))]
         (is (= #{"DEV" "A" "E" "F"} environments))
         (is (= 4 (count @pdb-x-queries))))))))

;;  the catalogs we get as http responses have flattened edge definitions
(def catalog-response
  (update-in catalog [:edges]
             #(map (fn [edge] {:relationship (:relationship edge)
                               :source_type (get-in edge [:source :type])
                               :source_title (get-in edge [:source :title])
                               :target_type (get-in edge [:target :type])
                               :target_title (get-in edge [:target :title])})
                   %)))


(deftest pull-catalogs-test
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/catalogs" pdb-x-queries stub-data-atom :certname)]

    (with-log-suppressed-unless-notable notable-pdb-event?
     (with-puppetdb-instance (utils/sync-config stub-handler)
       ;; store catalogs in PDB Y
       (doseq [c (map char (range (int \a) (int \g)))]
         (blocking-command-post (utils/pdb-cmd-url) "replace catalog" 6
                                (assoc catalog :certname (str c ".local"))))

       (let [local-catalogs (index-by :certname (get-json (utils/pdb-query-url) "/catalogs"))
             timestamps (ks/mapvals (comp to-date-time :producer_timestamp) local-catalogs)]
         (is (= 6 (count local-catalogs)))

         (reset! stub-data-atom
                 [;; time is newer than local, hash is different -> should pull
                  (assoc catalog-response
                         :certname "a.local"
                         :hash "a_different"
                         :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))
                         :environment "A")
                  ;; time is older than local, hash is different -> no pull
                  (assoc catalog-response
                         :certname "b.local"
                         :hash "b_different"
                         :producer_timestamp (t/minus (timestamps "b.local") (t/days 1)))
                  ;; time is the same, hash is the same -> no pull
                  (assoc catalog-response
                         :certname "c.local"
                         :hash (-> local-catalogs (get "c.local") :hash)
                         :producer_timestamp (timestamps "c.local"))
                  ;; time is the same, hash is lexically less -> no pull
                  (assoc catalog-response
                         :certname "d.local"
                         :hash "0000000000000000000000000000000000000000"
                         :producer_timestamp (timestamps "d.local"))
                  ;; time is the same, hash is lexically greater -> should pull
                  (assoc catalog-response
                         :certname "e.local"
                         :hash "ffffffffffffffffffffffffffffffffffffffff"
                         :producer_timestamp (timestamps "e.local")
                         :environment "E")
                  ;; timer is newer than local, hash is the same -> should pull
                  (assoc catalog-response
                         :certname "f.local"
                         :hash (-> local-catalogs (get "f.local") :hash)
                         :producer_timestamp (t/plus (timestamps "f.local") (t/days 1))
                         :environment "F")])

         ;; Send a sync command to PDB Y
         (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str)))

       ;; We should see that the sync happened, and that two catalog queries were made to PDB X
       (let [synced-catalogs (get-json (utils/pdb-query-url) "/catalogs")
             environments (->> synced-catalogs (map :environment) (into #{}))]
         (is (= #{"DEV" "A" "E" "F"} environments))
         (is (= 4 (count @pdb-x-queries))))))))

(deftest overlapping-sync
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler
                       "/pdb-x/v4/catalogs" pdb-x-queries stub-data-atom :certname)]

    (testing "overlapping sync"
      (with-log-suppressed-unless-notable notable-pdb-event?
        (with-puppetdb-instance (utils/sync-config stub-handler)
          (let [remote-url (utils/stub-url-str "/pdb-x/v4")]
            (is (contains?
                 (set (map :body (perform-overlapping-sync
                                  remote-url
                                  (utils/trigger-sync-url-str))))
                 (format "Refusing to sync from %s. Sync already in progress."
                         remote-url)))))))))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end test utils

(defn- get-reports [base-url certname]
  (first (get-json base-url "/reports"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn- get-factset [base-url certname]
  (first (get-json base-url "/factsets"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn- get-catalog [base-url certname]
  (first (get-json base-url "/catalogs"
                   {:query-params {:query (json/generate-string [:= :certname certname])}})))

(defn- get-node [base-url certname]
  (get-json base-url (str "/nodes/" certname)))

(defn- submit-catalog [endpoint catalog]
  (pdb-client/submit-command-via-http! (:command-url endpoint) "replace catalog" 6 catalog)
  @(block-until-results 100 (get-catalog (:query-url endpoint) (:certname catalog))))

(defn- submit-factset [endpoint facts]
  (pdb-client/submit-command-via-http! (:command-url endpoint) "replace facts" 4 facts)
  @(block-until-results 101 (get-factset (:query-url endpoint) (:certname facts))))

(defn- submit-report [endpoint report]
  (pdb-client/submit-command-via-http! (:command-url endpoint) "store report" 5 report)
  @(block-until-results 102 (get-reports (:query-url endpoint) (:certname report))))

(defn- deactivate-node [endpoint certname]
  (pdb-client/submit-command-via-http! (:command-url endpoint) "deactivate node" 3
                                       {:certname certname
                                        :producer_timestamp (t/plus (t/now) (t/years 10))})
  @(block-until-results 103 (:deactivated (get-node (:query-url endpoint) certname))))

(defn start-sync [& {:keys [from to]}]
  ;; Initiate pull
  (trigger-sync (base-url->str (:query-url from))
                (str (base-url->str (:sync-url to)) "/trigger-sync")))

(defn- sync [& {:keys [from to check-with check-for] :as args}]
  (start-sync :from from :to to)
  ;; Wait for the receiver to chew on its queue
  @(block-until-results 200 (check-with (:query-url to) check-for)))

(defn- without-timestamp [record]
  (dissoc record :timestamp))

(defn with-pdbs
  "Repeatedly call (gen-config [previously-started-instance-info...])
  and start a pdb instance for each returned config.  When gen-config
  returns false, call (f instance-1-info instance-2-info...).
  Suppress the log unless something \"notable\" happens."
  [gen-config f]
  (letfn [(spawn-pdbs [infos]
            (if-let [config (gen-config infos)]
              (let [mq-name (str "puppetlabs.puppetdb.commands-"
                                 (inc (count infos)))]
                (with-alt-mq mq-name
                  (with-puppetdb-instance config
                    (spawn-pdbs (conj infos
                                      (let [db (-> svcs/*server*
                                                   (get-service :PuppetDBServer)
                                                   service-context
                                                   :shared-globals
                                                   :scf-write-db)]
                                        {:mq-name mq-name
                                         :config config
                                         :server svcs/*server*
                                         :db db
                                         :query-fn (partial cli-svcs/query (tk-app/get-service svcs/*server* :PuppetDBServer))
                                         :server-url svcs/*base-url*
                                         :query-url (utils/pdb-query-url)
                                         :command-url (utils/pdb-cmd-url)
                                         :sync-url (utils/sync-url)}))))))
              (apply f infos)))]
    (with-log-suppressed-unless-notable notable-pdb-event?
      (without-jmx
       (spawn-pdbs [])))))

(defn default-pdb-configs [n]
  #(when (< (count %) n) (utils/sync-config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end tests

(deftest refuses-to-sync-to-unconfigured-remote
  (with-pdbs (comp #(ks/dissoc-in % [:sync :allow-unsafe-sync-triggers])
                  (default-pdb-configs 2))
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))

      (with-alt-mq (:mq-name pdb2)
        (is (.contains
             (:body (start-sync :from pdb1 :to pdb2))
             "Refusing to sync. PuppetDB is not configured to sync with"))))))

(deftest end-to-end-report-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-reports :check-for (:certname report)))

      (is (= (export/reports-for-node (:query-fn pdb1) (:certname report))
             (export/reports-for-node (:query-fn pdb2) (:certname report)))))))

(deftest end-to-end-factset-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-factset pdb1 facts))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-factset :check-for (:certname facts)))

      (is (=-after? without-timestamp
                    (get-factset (:query-url pdb1) (:certname facts))
                    (get-factset (:query-url pdb2) (:certname facts)))))))

(deftest end-to-end-catalog-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-catalog :check-for (:certname catalog)))

      (is (=-after? without-timestamp
                    (get-catalog (:query-url pdb1) (:certname catalog))
                    (get-catalog (:query-url pdb2) (:certname catalog)))))))

(deftest deactivate-then-sync
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
        ;; sync a node
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 catalog)
          (submit-factset pdb1 facts)
          (submit-report pdb1 report)
          (deactivate-node pdb1 certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2 :check-with get-node :check-for certname)
          (let [node (get-node (:query-url pdb2) certname)]
            (is (:deactivated node))))))))

(deftest sync-after-deactivate
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
        ;; sync a node
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 catalog)
          (submit-factset pdb1 facts)
          (submit-report pdb1 report))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2 :check-with get-node :check-for certname)
          (let [node (get-node (:query-url pdb2) certname)]
            (is (nil? (:deactivated node)))))

        ;; then deactivate and sync
        (with-alt-mq (:mq-name pdb1)
          (deactivate-node pdb1 certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2 :check-with get-node :check-for certname)
          (let [node (get-node (:query-url pdb2) certname)]
            (is (:deactivated node))))))))

(deftest periodic-sync
  (let [sync-interval "2s"]
    (let [periodic-sync-configs
          (fn [infos]
            (case (count infos)
              ;; infos length tells us which server we're handling.
              0 (utils/sync-config)
              1 (let [url (base-url->str (:server-url (infos 0)))]
                  (assoc (utils/sync-config)
                         :sync {:remotes [{:server_url url
                                           :interval sync-interval}]}))
              nil))
          facts-from #(get-factset (:query-url %) (:certname facts))]
      (with-pdbs periodic-sync-configs
        (fn [master mirror]
          (with-alt-mq (:mq-name master)
            (is (nil? (facts-from mirror)))
            (pdb-client/submit-command-via-http! (:command-url master)
                                                 "replace facts" 4 facts)
            @(block-until-results 100 (facts-from master)))
          @(block-until-results 100 (facts-from mirror))
          (is (=-after? without-timestamp
                        (facts-from mirror)
                        (facts-from master))))))))

(deftest pull-record-that-wouldnt-be-expired-locally
  (let [certname (:certname catalog)]
    (with-pdbs (default-pdb-configs 2)
      (fn [pdb1 pdb2]
        ;; add a node to pdb1
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 catalog))

        ;; expire it manually
        (jdbc/with-transacted-connection (:db pdb1)
          (scf-store/expire-node! certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2 :check-with get-node :check-for certname)
          ;; the node shouldn't be expired from pdb2's perspective, so it
          ;; should be pulled.
          (let [node (get-node (:query-url pdb2) certname)]
            (is (not (:expired node)))))))))

(deftest dont-pull-record-that-would-be-expired-locally
  (let [certname (:certname catalog)
        pdb-configs (fn [infos]
                      (case (count infos)
                        ;; infos length tells us which server we're handling.
                        0 (utils/sync-config)
                        1 (assoc (utils/sync-config)
                                 :node-ttl "1d")
                        nil))]
    (with-pdbs pdb-configs
      (fn [pdb1 pdb2]
        ;; add a node to pdb1 in the distant past
        (with-alt-mq (:mq-name pdb1)
          (submit-catalog pdb1 (assoc catalog :producer_timestamp (-> 3 t/weeks t/ago))))

        (with-alt-mq (:mq-name pdb2)
          (trigger-sync (base-url->str (:query-url pdb1))
                        (str (base-url->str (:sync-url pdb2)) "/trigger-sync"))
          ;; the other tests poll until a record exists to make sure sync worked, but that
          ;; doesn't make sense here. Just wait a little while instead.
          (Thread/sleep 5000)

          ;; the node should be expired from pdb2's perspective. So it
          ;; shouldn't get pulled
          (is (thrown+? [:status 404]
                        (get-node (:query-url pdb2) certname))))))))

(defn- server-queue-info [server name]
  (-> (get-service server :PuppetDBServer)
      service-context
      :broker
      (.getDestination (ActiveMQDestination/createDestination
                        cli-svcs/mq-endpoint
                        ActiveMQDestination/QUEUE_TYPE))
      .getDestinationStatistics))

(defn- wait-for-empty-queue [pdb name]
  (with-alt-mq (:mq-name pdb)
    (while (not (zero? (-> (server-queue-info (:server pdb) cli-svcs/mq-endpoint)
                           .getInflight
                           .getCount)))
      (Thread/sleep 100))))

(defn- event->map [event]
  {:level (str (.getLevel event))
   :message (.getMessage event)
   :map (.semlogMap (.getMarker event))})

(defn- ordered-matches? [predicates items]
  (loop [predicates predicates
         items items]
    (if-not (seq predicates)
      true
      (let [[predicate & predicates] predicates
            match (drop-while (complement predicate) items)]
        (when (seq match)
          (recur predicates (next match)))))))

(defn- elapsed-correct? [m]
  (if (#{"finished" "error"} (:event m))
    (is (number? (:elapsed m)))
    (is (nil? (:elapsed m)))))

(defn- ok-correct? [m]
  (case (:event m)
    ("finished") (is (:ok m))
    ("error") (is (not (:ok m)))
    true))

(defmacro verify-sync [event item]
  `(let [item# ~item
         m# (:map item#)]
     (when (= "sync" (:phase m#))
       (and
        (is (= ~event (:event m#)))
        (is (= "INFO" (:level item#)))
        (is (string? (:remote m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-entity-sync [event name xfer fail item]
  `(let [item# ~item
         m# (:map item#)]
     (assert (or (zero? ~fail) (not= "start" (:event m#))))
     (when (and (= "entity" (:phase m#))
                (= ~name (:entity m#)))
       (and
        (is (= "INFO" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:remote m#)))
        (is (= ~xfer (:transferred m#)))
        (is (= ~fail (:failed m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-record-sync [event name item]
  `(let [item# ~item
         m# (:map item#)]
     (when (and (= "record" (:phase m#))
                (= ~name (:entity m#)))
       (and
        (is (= "DEBUG" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:remote m#)))
        (is (string? (:certname m#)))
        (is (string? (:hash m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(defmacro verify-deactivate-sync [event item]
  `(let [item# ~item
         m# (:map item#)]
     (when (= "deactivate" (:phase m#))
       (and
        (is (= "DEBUG" (:level item#)))
        (is (= ~event (:event m#)))
        (is (string? (:certname m#)))
        (is (instance? java.util.Date (:producer_timestamp m#)))
        (ok-correct? m#)
        (elapsed-correct? m#)))))

(deftest sync-logging
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (let [events (let [log (atom [])]
                     (with-log-level ":sync" :debug
                       (with-logging-to-atom ":sync" log
                         (let [certname (:certname catalog)]
                           ;; Submit a normal sequence of commands
                           (with-alt-mq (:mq-name pdb1)
                             (submit-catalog pdb1 catalog)
                             (submit-factset pdb1 facts)
                             (submit-report pdb1 report)
                             (deactivate-node pdb1 certname))
                           (with-alt-mq (:mq-name pdb2)
                             (sync :from pdb1 :to pdb2
                                   :check-with get-node :check-for certname)))))
                     (map event->map @log))]
        ;; Verify expected partial orderings
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "factsets" 0 0 %)
              #(verify-record-sync "start" "factsets" %)
              #(verify-record-sync "finished" "factsets" %)
              #(verify-entity-sync "finished" "factsets" 1 0 %)
              #(verify-sync "finished" %)]
             events))
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "catalogs" 0 0 %)
              #(verify-record-sync "start" "catalogs" %)
              #(verify-record-sync "finished" "catalogs" %)
              #(verify-entity-sync "finished" "catalogs" 1 0 %)
              #(verify-sync "finished" %)]
             events))
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "reports" 0 0 %)
              #(verify-record-sync "start" "reports" %)
              #(verify-record-sync "finished" "reports" %)
              #(verify-entity-sync "finished" "reports" 1 0 %)
              #(verify-sync "finished" %)]
             events))
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "nodes" 0 0 %)
              #(verify-deactivate-sync "start" %)
              #(verify-deactivate-sync "finished" %)
              #(verify-entity-sync "finished" "nodes" 1 0 %)
              #(verify-sync "finished" %)]
             events))))))

(deftest sync-logging-entity-failure
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (let [events
            ;; Force a transfer error while syncing a factset.
            (let [log (atom [])]
              (with-log-level ":sync" :debug
                (with-logging-to-atom ":sync" log
                  (let [certname (:certname catalog)]
                    (with-alt-mq (:mq-name pdb1)
                      (submit-factset pdb1 facts))
                    (with-alt-mq (:mq-name pdb2)
                      (with-redefs [query-record-and-transfer!
                                    (fn [& args]
                                      (throw+ {:type ::syncc/remote-host-error
                                               :error-response {:status 404}}))]
                        (trigger-sync (base-url->str (:query-url pdb1))
                                      (str (base-url->str (:sync-url pdb2))
                                           "/trigger-sync"))))
                    (wait-for-empty-queue pdb1 cli-svcs/mq-endpoint))))
              (map event->map @log))]
        ;; Check that factsets :failed is 1.
        (is (ordered-matches?
             [#(verify-sync "start" %)
              #(verify-entity-sync "start" "factsets" 0 0 %)
              #(verify-entity-sync "finished" "factsets" 0 1 %)
              #(verify-sync "finished" %)]
             events))))))

;;; Events tests

(defn mock-fn
  "Create a mock version of a 0-arity function that can tell you if it has been
  called."
  ([] (mock-fn nil))
  ([f] (let [was-called (atom false)]
        (reify
          clojure.lang.IFn
          (invoke [_]
            (let [result (if f (f))]
              (reset! was-called true)
              result))
          sync-test-protos/IMockFn
          (called? [_] @was-called)))))

(defn run-test-for-var
  "Depending on how you're running your tests, it can be tricky to invoke
  another test (lein can yank them out from under you). This should do it more
  reliably."
  [test-var]
  (let [m (meta test-var)
        f (or (:test m) (:leiningen/skipped-test m))]
     (if f
       (f)
       (throw (Exception. (str "Couldn't find a test fn attached to var " (:name meta)))))))

(deftest successful-sync-event-test
  (with-redefs [events/successful-sync! (mock-fn)
                events/failed-sync! (mock-fn)
                events/failed-request! (mock-fn)]
    (run-test-for-var #'pull-reports-test)
    (is (= true (called? events/successful-sync!)))
    (is (= false (called? events/failed-sync!)))
    (is (= false (called? events/failed-request!)))))

(deftest failed-sync-event-test
  ;; this is a very noisy test...
  (binding [clojure.tools.logging/*logger-factory*
            clojure.tools.logging.impl/disabled-logger-factory]
   (with-redefs [events/successful-sync! (mock-fn)
                 events/failed-sync! (mock-fn)
                 events/failed-request! (mock-fn)]
     (try
       (sync-from-remote! #() #() {:url "http://localhost:1234/bogus"} (parse-period "42s"))
       (catch Exception _))
     (is (= false (called? events/successful-sync!)))
     (is (= true (called? events/failed-sync!)))
     (is (= true (called? events/failed-request!))))))

;;; HTTPS
(deftest pull-with-https
  (let [seen-http-get-opts (atom [])
        remote-host-url "https://some-host"]
    ;; Stub http/get to always return empty content, but to remember the options
    ;; passed to it.
   (with-redefs [http/get (fn [url opts]
                            (when (.startsWith url remote-host-url)
                              (swap! seen-http-get-opts conj opts))
                            {:status 200
                             :body (java.io.StringReader. "[]")})]
     ;; Run a pdb with https
     (with-puppetdb-instance (assoc (utils/sync-config)
                                    :jetty {:ssl-port 0
                                            :ssl-host "0.0.0.0"
                                            :ssl-cert "test-resources/localhost.pem"
                                            :ssl-key "test-resources/localhost.key"
                                            :ssl-ca-cert "test-resources/ca.pem"
                                            :ssl-protocols "TLSv1,TLSv1.1"})
       ;; Trigger a sync; the url doesn't matter, as the stubbed http/get will be used
       (http/post (utils/trigger-sync-url-str)
                  {:headers {"content-type" "application/json"}
                   :body (json/generate-string {:remote_host_path remote-host-url})
                   :ssl-cert "test-resources/localhost.pem"
                   :ssl-key "test-resources/localhost.key"
                   :ssl-ca-cert "test-resources/ca.pem"})
       ;; Check that the ssl was configured when making sync requests
       (is (pos? (count @seen-http-get-opts)))
       (doseq [opts @seen-http-get-opts]
         (is opts)
         (is (contains? opts :ssl-cert))
         (is (contains? opts :ssl-key))
         (is (contains? opts :ssl-ca-cert)))))))
