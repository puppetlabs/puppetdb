(ns puppetlabs.pe-puppetdb-extensions.sync.pull-changes-test
  (:refer-clojure :exclude [sync])
  (:require [clojure.test :refer :all :exclude [report]]
            [clj-time.coerce :refer [to-date-time]]
            [clj-time.core :as t]
            [puppetlabs.http.client.sync :as http-client-sync]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.pe-puppetdb-extensions.sync.core :as sync-core]
            [puppetlabs.pe-puppetdb-extensions.sync.events :as events]
            [puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils
             :refer [logging-query-routes run-test-for-var
                     perform-sync trigger-sync
                     facts catalog]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance index-by blocking-command-post
                     sync-config with-ext-instances json-response]]
            [puppetlabs.puppetdb.cheshire :as json :refer [generate-string]]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.test-protocols :refer [called?]]
            [puppetlabs.puppetdb.testutils :refer [mock-fn]]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.services :as svcs :refer [get-json]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.time :refer [parse-period]]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.puppetdb.middleware :as mid]))

;;; These tests isolate PDB-Y. We store some fixture data in it, then synthesize
;;; a sync command. We serve requests back to PDB-X with a mock so we can
;;; check the right ones were made. Finally, we check that PDB-Y has the right data
;;; after sync.

(defn notable-pull-changes-event? [event] true)

(use-fixtures :once
  (fn [f]
    (binding [svcs/*notable-log-event?* notable-pull-changes-event?]
      (f))))

(defn stub-reports-summary-route [content-atom]
  (cmdi/GET "/pdb-x/sync/v1/reports-summary" []
            (json-response @content-atom)))

(deftest pull-reports-test
  (let [report-1 (-> reports :basic reports/report-query->wire-v7)
        report-2 (assoc report-1 :certname "bar.local")
        cert1 (:certname report-1)
        cert2 (:certname report-2)
        pdb-x-queries (atom [])
        stub-data-atom (atom [])
        bucketed-reports-atom (atom {})
        stub-handler (cmdi/routes
                      (stub-reports-summary-route bucketed-reports-atom)
                      (logging-query-routes :reports pdb-x-queries
                                            stub-data-atom :hash))]
    (with-log-suppressed-unless-notable notable-pull-changes-event?
      (with-ext-instances [pdb (sync-config stub-handler)]
        ;; store two reports in PDB Y
        (blocking-command-post (utils/pdb-cmd-url) cert1 "store report" 7 report-1)
        (blocking-command-post (utils/pdb-cmd-url) cert2 "store report" 7 report-2)

        (let [created-report-1 (first (svcs/get-reports (utils/pdb-query-url) cert1))
              created-report-2 (first (svcs/get-reports (utils/pdb-query-url) cert2))]
          (is (= "3.0.1" (:puppet_version created-report-2)))

          ;; Set up pdb-x as a stub where 1 report has a different hash
          (reset! stub-data-atom
                  [(assoc report-2
                          :hash "something totally different"
                          :producer_timestamp "2011-01-01T12:11:00-03:30"
                          :puppet_version "4.0.0")
                   (assoc report-1
                          :certname "foo.local"
                          :hash (:hash created-report-1)
                          :start_time "2011-01-01T15:00:00Z")])

          (reset! bucketed-reports-atom
                  {"2011-01-01T15:00:00.000Z" "a different hash"})

          ;; Pull data from pdb-x to pdb-y
          (perform-sync (utils/stub-url-str "/pdb-x/query/v4")
                        (utils/trigger-sync-url-str))

          ;; We should see that the sync happened, and that only one
          ;; report was pulled from PDB X
          (let [puppet-versions (->> (svcs/get-reports (utils/pdb-query-url)
                                                       (:certname report-2))
                                     (map :puppet_version)
                                     set)]
            (is (= #{"4.0.0" "3.0.1"} puppet-versions))
            (is (= 2 (count @pdb-x-queries)))))))))

(deftest pull-factsets-test
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-routes :factsets pdb-x-queries
                                           stub-data-atom :certname)]
    (with-log-suppressed-unless-notable notable-pull-changes-event?
      (with-ext-instances [pdb (sync-config stub-handler)]
        ;; store factsets in PDB Y
        (doseq [c (map char (range (int \a) (int \g)))]
          (let [certname (str c ".local")]
            (blocking-command-post (utils/pdb-cmd-url) certname "replace facts" 4
                                   (assoc facts :certname certname))))

        (let [local-factsets (index-by :certname (get-json (utils/pdb-query-url)
                                                           "/factsets"))
              timestamps (ks/mapvals (comp to-date-time :producer_timestamp)
                                     local-factsets)]
          (is (= 6 (count local-factsets)))

          (reset! stub-data-atom
                  [;; time is newer than local, hash is different -> should pull
                   (assoc facts
                          :certname "a.local"
                          :hash "a_different"
                          :producer_timestamp (t/plus (timestamps "a.local")
                                                      (t/days 1))
                          :environment "A")
                   ;; time is older than local, hash is different -> no pull
                   (assoc facts
                          :certname "b.local"
                          :hash "b_different"
                          :producer_timestamp (t/minus (timestamps "b.local")
                                                       (t/days 1)))
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
                          :producer_timestamp (t/plus (timestamps "f.local")
                                                      (t/days 1))
                          :environment "F")])

          ;; Send a sync command to PDB Y
          (perform-sync (utils/stub-url-str "/pdb-x/query/v4")
                        (utils/trigger-sync-url-str)))

        ;; We should see that the sync happened, and that a summary
        ;; query and a bulk record transfer query where made to PDB X
        (let [synced-factsets (get-json (utils/pdb-query-url) "/factsets")
              environments (->> synced-factsets (map :environment) (into #{}))]
          (is (= #{"DEV" "A" "E" "F"} environments))
          (is (= 2 (count @pdb-x-queries))))))))

;;  the catalogs we get as http responses have flattened edge definitions
(def catalog-response
  (update-in catalog [:edges]
             #(map (fn [edge] {:relationship (:relationship edge)
                               :source_type (get-in edge [:source :type])
                               :source_title (get-in edge [:source :title])
                               :target_type (get-in edge [:target :type])
                               :target_title (get-in edge [:target :title])})
                   %)))

(defn stub-catalogs-summary-route [content-atom]
  (cmdi/GET "/pdb-x/sync/v1/catalogs-summary" []
    (json-response @content-atom)))

(deftest pull-catalogs-test
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        bucketed-catalogs-atom (atom {})
        stub-handler (cmdi/routes
                      (stub-catalogs-summary-route bucketed-catalogs-atom)
                      (logging-query-routes :catalogs
                                            pdb-x-queries
                                            stub-data-atom
                                            :transaction_uuid))]
    (with-log-suppressed-unless-notable notable-pull-changes-event?
      (with-ext-instances [pdb (sync-config stub-handler)]
        ;; store catalogs in PDB Y
        (doseq [c ["a" "b"]]
          (let [certname (str c ".local")]
            (blocking-command-post (utils/pdb-cmd-url) certname "replace catalog" 8
                                   (assoc catalog :certname certname))))

        (let [local-catalogs (index-by :certname (get-json (utils/pdb-query-url) "/catalogs"))
              timestamps (ks/mapvals (comp to-date-time :producer_timestamp)
                                     local-catalogs)
              transaction-uuids (ks/mapvals :transaction_uuid local-catalogs)]
          (is (= 2 (count local-catalogs)))

          (reset! stub-data-atom
                  [ ;; same transaction_uuid -> no pull
                   (assoc catalog-response
                          :certname "a.local"
                          :producer_timestamp (timestamps "a.local")
                          :transaction_uuid (transaction-uuids "a.local")
                          :environment "A")

                   ;; certname and timestamp same but different transaction_uuid -> pull
                   (assoc catalog-response
                          :certname "b.local"
                          :producer_timestamp (timestamps "b.local")
                          :transaction_uuid "a8b08e2a-eeb1-4322-b241-bfdf151d294b"
                          :environment "B")])

          (reset! bucketed-catalogs-atom
                  {"2014-07-10T22:00:00.000Z" "a different hash"})

          ;; Send a sync command to PDB Y
          (perform-sync (utils/stub-url-str "/pdb-x/query/v4")
                        (utils/trigger-sync-url-str)))

        ;; We should see that the sync happened, and a summary query and bulk record query were made to PDB X
        (let [local-catalogs (get-json (utils/pdb-query-url) "/catalogs")
              environments (->> local-catalogs (map :environment) (into #{}))]
          (is (= #{"DEV" "B"} environments))
          (is (= 2 (count @pdb-x-queries))))))))

(deftest pull-with-https
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-routes (logging-query-routes
                      :factsets pdb-x-queries stub-data-atom :hash)
        pdb-config (assoc (sync-config stub-routes)
                          :jetty {:ssl-port 0
                                  :ssl-host "0.0.0.0"
                                  :ssl-cert "test-resources/localhost.pem"
                                  :ssl-key "test-resources/localhost.key"
                                  :ssl-ca-cert "test-resources/ca.pem"
                                  :ssl-protocols "TLSv1,TLSv1.1"})]
    ;; Run a pdb with https
    (with-ext-instances [pdb pdb-config]
      (let [remote-host-path (utils/stub-url-str "/pdb-x/query/v4")]
        (is (.startsWith remote-host-path "https"))
        ;; Trigger a sync against the stub, using https
        (http-client-sync/post (utils/trigger-sync-url-str)
                               {:headers {"content-type" "application/json"}
                                :body (generate-string {:remote_host_path remote-host-path})
                                :ssl-cert "test-resources/localhost.pem"
                                :ssl-key "test-resources/localhost.key"
                                :ssl-ca-cert "test-resources/ca.pem"})
        ;; Check that some of the requests went through
        (is (pos? (count @pdb-x-queries)))))))

(defn perform-overlapping-sync [source-pdb-url dest-sync-url]
  (let [stop-here (promise)]
    (with-redefs
      [sync-core/sync-from-remote! (fn [& args] @stop-here)]
      ;; the atom in sync-with! will cause the first of these calls to block,
      ;; and the second call to release the block.
      (let [block-first #(let [res (trigger-sync source-pdb-url dest-sync-url)]
                           (deliver stop-here nil)
                           res)
            a (future (block-first))
            b (future (block-first))]
        [@a @b]))))

(deftest overlapping-sync
  (let [pdb-x-queries (atom [])
        stub-data-atom (atom [])
        stub-handler (logging-query-routes
                      "/pdb-x/query/v4/catalogs" pdb-x-queries stub-data-atom :certname)]

    (testing "overlapping sync"
      (with-log-suppressed-unless-notable notable-pull-changes-event?
        (with-ext-instances [pdb (sync-config stub-handler)]
          (let [remote-url (utils/stub-url-str "/pdb-x/query/v4")]
            (is (contains?
                 (set (map :body (perform-overlapping-sync
                                  remote-url
                                  (utils/trigger-sync-url-str))))
                 (format "Refusing to sync from %s. Sync already in progress."
                         remote-url)))))))))

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
      (with-open [client (http-client-sync/create-client {})]
       (try
         (sync-core/sync-from-remote! #() #() #() {:url "http://localhost:1234/bogus" :client client} (parse-period "42s"))
         (catch Exception _)))
      (is (= false (called? events/successful-sync!)))
      (is (= true (called? events/failed-sync!)))
      (is (= true (called? events/failed-request!))))))
