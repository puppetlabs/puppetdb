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
            [compojure.core :refer [POST routes ANY GET context]]
            [clojure.tools.logging :as log]
            [clj-time.coerce :refer [to-date-time]]
            [clj-time.core :as t]
            [puppetlabs.kitchensink.core :as ks]
            [slingshot.test]
            [clojure.core.match :refer [match]]))

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
                     summary-data (map #(dissoc % :content) stub-data)]
                 (when-let [query (vec (json/parse-string (query-params "query")))]
                   (swap! requests-atom conj query)
                   (cond
                     (= "extract" (first query))
                     (json-response summary-data)

                     (= ["=" (name record-identity-key)] (take 2 query))
                     (let [[_ _ record-hash] query]
                       (json-response [(get-in stub-data-index [record-hash :content])]))))))

          ;; fallback routes, for data that wasn't explicitly stubbed
          (context "/pdb-x/v4" []
                   (GET "/reports" [] (json-response []))
                   (GET "/factsets" [] (json-response []))
                   (GET "/catalogs" [] (json-response [])))))

(defn trigger-sync [source-pdb-url dest-sync-url]
 (http/post dest-sync-url
            {:headers {"content-type" "application/json"}
             :body (json/generate-string {:remote_host_path source-pdb-url})
             :throw-entire-message true}))

(defn perform-sync [source-pdb-url dest-sync-url]
  (svcs/until-consumed #(trigger-sync source-pdb-url dest-sync-url)))

;; alias to a different name because 'sync' means 'synchronous' here, and that's REALLY confusing.
(def blocking-command-post svcs/sync-command-post)

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
        (reset! stub-data-atom [{:certname "bar.local"
                                 :hash "something totally different"
                                 :start_time "2011-01-03T15:00:00Z"
                                 :content (assoc report-2 :puppet_version "4.0.0")}
                                {:certname "foo.local"
                                 :hash (:hash created-report-1)
                                 :start_time "2011-01-01T15:00:00Z"
                                 :content report-1}])

        ;; Pull data from pdb-x to pdb-y
        (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str))

        ;; We should see that the sync happened, and that only one report was pulled from PDB X
        (let [puppet-versions (map :puppet_version (export/reports-for-node (utils/pdb-url) (:certname report-2)))]
          (is (= #{"4.0.0" "3.0.1"} (set puppet-versions)))
          (is (= 2 (count @pdb-x-queries))))))))

(def facts {:certname "foo.local"
            :environment "DEV"
            :values tuf/base-facts
            :producer_timestamp (new java.util.Date)})

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
                 {:certname "a.local"
                  :hash "a_different"
                  :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))
                  :content (assoc facts
                                  :certname "a.local"
                                  :environment "A")}
                 ;; time is older than local, hash is different -> no pull
                 {:certname "b.local"
                  :hash "b_different"
                  :producer_timestamp (t/minus (timestamps "b.local") (t/days 1))
                  :content (assoc facts :certname "b.local")}
                 ;; time is the same, hash is the same -> no pull
                 {:certname "c.local"
                  :hash (-> local-factsets (get "c.local") :hash)
                  :producer_timestamp (timestamps "c.local")
                  :content (assoc facts :certname "c.local")}
                 ;; time is the same, hash is lexically less -> no pull
                 {:certname "d.local"
                  :hash "0000000000000000000000000000000000000000"
                  :producer_timestamp (timestamps "d.local")
                  :content (assoc facts :certname "d.local")}
                 ;; time is the same, hash is lexically greater -> should pull
                 {:certname "e.local"
                  :hash "ffffffffffffffffffffffffffffffffffffffff"
                  :producer_timestamp (timestamps "e.local")
                  :content (assoc facts
                                  :certname "e.local"
                                  :environment "E")}
                 ;; time is newer than local, hash is the same -> should pull
                 {:certname "f.local"
                  :hash (-> local-factsets (get "f.local") :hash)
                  :producer_timestamp (t/plus (timestamps "f.local") (t/days 1))
                  :content (assoc facts
                                  :certname "f.local"
                                  :environment "F")}])

        ;; Send a sync command to PDB Y
        (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str)))

      ;; We should see that the sync happened, and that one summary query and two factset querys where made to PDB X
      (let [synced-factsets (get-json (utils/pdb-url) "/factsets")
            environments (->> synced-factsets (map :environment) (into #{}))]
        (is (= #{"DEV" "A" "E" "F"} environments))
        (is (= 4 (count @pdb-x-queries)))))))

(deftest pull-catalogs-test
  (let [catalog (get-in wire-catalogs [6 :basic])
        pdb-x-queries (atom [])
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
                 {:certname "a.local"
                  :hash "a_ different"
                  :producer_timestamp (t/plus (timestamps "a.local") (t/days 1))
                  :content (assoc catalog
                                  :certname "a.local"
                                  :environment "A")}
                 ;; time is older than local, hash is different -> no pull
                 {:certname "b.local"
                  :hash "different"
                  :producer_timestamp (t/minus (timestamps "b.local") (t/days 1))
                  :content (assoc catalog :certname "b.local")}
                 ;; time is the same, hash is the same -> no pull
                 {:certname "c.local"
                  :hash (-> local-catalogs (get "c.local") :hash)
                  :producer_timestamp (timestamps "c.local")
                  :content (assoc catalog :certname "c.local")}
                 ;; time is the same, hash is lexically less -> no pull
                 {:certname "d.local"
                  :hash "0000000000000000000000000000000000000000"
                  :producer_timestamp (timestamps "d.local")
                  :content (assoc catalog :certname "d.local")}
                 ;; time is the same, hash is lexically greater -> should pull
                 {:certname "e.local"
                  :hash "ffffffffffffffffffffffffffffffffffffffff"
                  :producer_timestamp (timestamps "e.local")
                  :content (assoc catalog
                                  :certname "e.local"
                                  :environment "E")}
                 ;; timer is newer than local, hash is the same -> should pull
                 {:certname "f.local"
                  :hash (-> local-catalogs (get "f.local") :hash)
                  :producer_timestamp (t/plus (timestamps "f.local") (t/days 1))
                  :content (assoc catalog
                                  :certname "f.local"
                                  :environment "F")}])

        ;; Send a sync command to PDB Y
        (perform-sync (utils/stub-url-str "/pdb-x/v4") (utils/trigger-sync-url-str)))

      ;; We should see that the sync happened, and that two catalog queries were made to PDB X
      (let [synced-catalogs (get-json (utils/pdb-url) "/catalogs")
            environments (->> synced-catalogs (map :environment) (into #{}))]
        (is (= #{"DEV" "A" "E" "F"} environments))
        (is (= 4 (count @pdb-x-queries)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; End to end tests

(defn- with-n-pdbs
  ([n f] (muting-amq-shutdown-log-noise
          (svcs/without-jmx
           (with-n-pdbs n f []))))
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
          ;; pdb2 pulls data from pdb1
          (trigger-sync (base-url->str (:service-url pdb1))
                        (str (base-url->str (:sync-url pdb2)) "/trigger-sync"))

          ;; let pdb2 chew on its queue
          @(rt/block-until-results 200 (export/reports-for-node (:service-url pdb2) (:certname report)))

          (is (= (export/reports-for-node (:service-url pdb1) (:certname report))
                 (export/reports-for-node (:service-url pdb2) (:certname report)))))))))
