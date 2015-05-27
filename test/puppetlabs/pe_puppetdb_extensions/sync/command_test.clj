(ns puppetlabs.pe-puppetdb-extensions.sync.command-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :exclude [report] :refer :all]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clj-http.client :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils :as testutils :refer [=-after?]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :as rt]
            [puppetlabs.pe-puppetdb-extensions.sync.command :refer :all]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance index-by json-request json-response get-json blocking-command-post]]
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
            [slingshot.test]
            [clojure.core.match :refer [match]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.time :refer [parse-period]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Sync stream comparison tests

(defn records-to-fetch' [local remote]
  (records-to-fetch report-key (constantly 0) local remote (t/now) (parse-period "0s")))

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
                                    [] (map generate-report (range ten-billion))
                                    (t/now)
                                    (parse-period "0s"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Tests for the test infrastructure

(defmacro with-alt-mq [mq-name & body]
  `(with-redefs [puppetlabs.puppetdb.cli.services/mq-endpoint ~mq-name]
     (do ~@body)))

(defmacro muting-amq-shutdown-log-noise [& body]
  `(binding [svcs/*extra-appender-config* [:filter {:class "ch.qos.logback.core.filter.EvaluatorFilter"}
                                          [:evaluator
                                           [:expression (str "String m = throwable.getMessage(); "
                                                             "return javax.jms.JMSException.class.isInstance(throwable) && "
                                                             "  m.contains(\"peer\") && "
                                                             "  m.contains(\"stopped\"); ")]]
                                          [:OnMatch "DENY"]
                                          [:OnMismatch "NEUTRAL"]]]
     ~@body))

(deftest two-instance-test
  (muting-amq-shutdown-log-noise
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
                @(rt/block-until-results 100 (export/reports-for-node (utils/pdb-url) (:certname report))))))))))))

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
             :throw-entire-message true}))

(defn perform-sync [source-pdb-url dest-sync-url]
  (svcs/until-consumed #(trigger-sync source-pdb-url dest-sync-url)))

(defn get-first-report [pdb-url certname]
  (first (get-json pdb-url (str "/reports/?query=" (json/generate-string [:= :certname certname])))))

(deftest pull-reports-test
  (let [report-1 (-> reports :basic tur/munge-example-report-for-storage)
        report-2 (assoc report-1 :certname "bar.local")
        pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/reports" pdb-x-queries stub-data-atom :hash)]

    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store two reports in PDB Y
      (blocking-command-post (utils/pdb-url) "store report" 5 report-1)
      (blocking-command-post (utils/pdb-url) "store report" 5 report-2)

      (let [created-report-1 (get-first-report (utils/pdb-url) (:certname report-1))
            created-report-2 (get-first-report (utils/pdb-url) (:certname report-2))]
        (is (= "3.0.1" (:puppet_version created-report-2)))

        ;; Set up pdb-x as a stub where 1 report has a different hash
        (reset! stub-data-atom [(assoc report-2
                                       :certname "bar.local"
                                       :hash "something totally different"
                                       :start_time "2011-01-03T15:00:00Z"
                                       :puppet_version "4.0.0")
                                (assoc report-1
                                       :certname "foo.local"
                                       :hash (:hash created-report-1)
                                       :start_time "2011-01-01T15:00:00Z")])

        ;; Pull data from pdb-x to pdb-y
        (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str))

        ;; We should see that the sync happened, and that only one report was pulled from PDB X
        (let [puppet-versions (map :puppet_version (export/reports-for-node (utils/pdb-url) (:certname report-2)))]
          (is (= #{"4.0.0" "3.0.1"} (set puppet-versions)))
          (is (= 2 (count @pdb-x-queries))))))))

(deftest pull-factsets-test
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-handler "/pdb-x/v4/factsets" pdb-x-queries stub-data-atom :certname)]
    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store factsets in PDB Y
      (doseq [c (map char (range (int \a) (int \g)))]
        (blocking-command-post (utils/pdb-url) "replace facts" 4
                               (assoc facts :certname (str c ".local"))))

      (let [local-factsets (index-by :certname (get-json (utils/pdb-url) "/factsets"))
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
      (let [synced-factsets (get-json (utils/pdb-url) "/factsets")
            environments (->> synced-factsets (map :environment) (into #{}))]
        (is (= #{"DEV" "A" "E" "F"} environments))
        (is (= 4 (count @pdb-x-queries)))))))

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

    (with-puppetdb-instance (utils/sync-config stub-handler)
      ;; store catalogs in PDB Y
      (doseq [c (map char (range (int \a) (int \g)))]
        (blocking-command-post (utils/pdb-url) "replace catalog" 6
                               (assoc catalog :certname (str c ".local"))))

      (let [local-catalogs (index-by :certname (get-json (utils/pdb-url) "/catalogs"))
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
      (let [synced-catalogs (get-json (utils/pdb-url) "/catalogs")
            environments (->> synced-catalogs (map :environment) (into #{}))]
        (is (= #{"DEV" "A" "E" "F"} environments))
        (is (= 4 (count @pdb-x-queries)))))))

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
  (pdb-client/submit-command-via-http! (:service-url endpoint) "replace catalog" 6 catalog)
  @(rt/block-until-results 100 (get-catalog (:service-url endpoint) (:certname catalog))))

(defn- submit-factset [endpoint facts]
  (pdb-client/submit-command-via-http! (:service-url endpoint) "replace facts" 4 facts)
  @(rt/block-until-results 101 (get-factset (:service-url endpoint) (:certname facts))))

(defn- submit-report [endpoint report]
  (pdb-client/submit-command-via-http! (:service-url endpoint) "store report" 5 report)
  @(rt/block-until-results 102 (get-reports (:service-url endpoint) (:certname report))))

(defn- deactivate-node [endpoint certname]
  (pdb-client/submit-command-via-http! (:service-url endpoint) "deactivate node" 3
                                       {:certname certname
                                        :producer_timestamp (t/plus (t/now) (t/years 10))})
  @(rt/block-until-results 103 (:deactivated (get-node (:service-url endpoint) certname))))

(defn- sync [& {:keys [from to check-with check-for]}]
  ;; pdb2 pulls data from pdb1
  (trigger-sync (base-url->str (:service-url from))
                (str (base-url->str (:sync-url to)) "/trigger-sync"))

  ;; let pdb2 chew on its queue
  @(rt/block-until-results 200 (check-with (:service-url to) check-for)))

(defn- without-timestamp [record]
  (dissoc record :timestamp))

(defn- with-pdbs
  "Repeatedly call (gen-config [previously-started-instance-info...])
  and start a pdb instance for each returned config.  When gen-config
  returns false, call (f instance-1-info instance-2-info...)."
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
                                         :service-url (utils/pdb-url)
                                         :sync-url (utils/sync-url)}))))))
              (apply f infos)))]
    (muting-amq-shutdown-log-noise
     (svcs/without-jmx
      (spawn-pdbs [])))))

(defn- default-pdb-configs [n]
  #(when (< (count %) n) (utils/sync-config)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end tests

(deftest end-to-end-report-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-report pdb1 report))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-reports :check-for (:certname report)))

      (is (= (export/reports-for-node (:service-url pdb1) (:certname report))
             (export/reports-for-node (:service-url pdb2) (:certname report)))))))

(deftest end-to-end-factset-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-factset pdb1 facts))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-factset :check-for (:certname facts)))

      (is (=-after? without-timestamp
                    (get-factset (:service-url pdb1) (:certname facts))
                    (get-factset (:service-url pdb2) (:certname facts)))))))

(deftest end-to-end-catalog-replication
  (with-pdbs (default-pdb-configs 2)
    (fn [pdb1 pdb2]
      (with-alt-mq (:mq-name pdb1)
        (submit-catalog pdb1 catalog))

      (with-alt-mq (:mq-name pdb2)
        (sync :from pdb1 :to pdb2 :check-with get-catalog :check-for (:certname catalog)))

      (is (=-after? without-timestamp
                    (get-catalog (:service-url pdb1) (:certname catalog))
                    (get-catalog (:service-url pdb2) (:certname catalog)))))))

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
          (let [node (get-node (:service-url pdb2) certname)]
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
          (let [node (get-node (:service-url pdb2) certname)]
            (is (nil? (:deactivated node)))))

        ;; then deactivate and sync
        (with-alt-mq (:mq-name pdb1)
          (deactivate-node pdb1 certname))

        (with-alt-mq (:mq-name pdb2)
          (sync :from pdb1 :to pdb2 :check-with get-node :check-for certname)
          (let [node (get-node (:service-url pdb2) certname)]
            (is (:deactivated node))))))))

(deftest periodic-sync
  (let [sync-interval 2]
    (let [periodic-sync-configs
          (fn [infos]
            (case (count infos)
              ;; infos length tells us which server we're handling.
              0 (utils/sync-config)
              1 (let [url (base-url->str (:service-url (infos 0)))]
                  (assoc (utils/sync-config)
                         :sync {:remotes [{:endpoint url
                                           :interval sync-interval}]}))
              nil))
          facts-from #(get-factset (:service-url %) (:certname facts))]
      (with-pdbs periodic-sync-configs
        (fn [master mirror]
          (with-alt-mq (:mq-name master)
            (pdb-client/submit-command-via-http! (:service-url master)
                                                 "replace facts" 4 facts)
            @(rt/block-until-results 100 (facts-from master)))
          (is (nil? (facts-from mirror)))
          @(rt/block-until-results 100 (facts-from mirror))
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
          (let [node (get-node (:service-url pdb2) certname)]
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
          (trigger-sync (base-url->str (:service-url pdb1))
                        (str (base-url->str (:sync-url pdb2)) "/trigger-sync"))
          ;; the other tests poll until a record exists to make sure sync worked, but that
          ;; doesn't make sense here. Just wait a little while instead.
          (Thread/sleep 5000)

          ;; the node should be expired from pdb2's perspective. So it
          ;; shouldn't get pulled
          (is (thrown+? [:status 404]
                        (get-node (:service-url pdb2) certname))))))))
