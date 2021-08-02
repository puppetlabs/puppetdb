(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.command.constants :as cmd-consts]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.kitchensink.core :refer [dissoc-in]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.db :refer [*db*]]
            [puppetlabs.puppetdb.testutils.cli
             :refer [get-nodes get-catalogs get-factsets get-reports munge-tar-map
                     example-catalog example-report
                     example-facts example-certname example-certname2
                     example-nodes example-configure-expiration-true
                     example-configure-expiration-false get-summary-stats
                     filter-reports]]
            [puppetlabs.puppetdb.testutils.tar :refer [tar->map]]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.testutils.catalog-inputs :refer [sample-input-cmds]]
            [puppetlabs.puppetdb.time :as time]))

(use-fixtures :each tu/call-with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [export-out-file (tu/temp-file "export-test" ".tar.gz")
        current-time (time/now)
        catalog-input-cmd (-> (sample-input-cmds)
                              (get "host-1")
                              (update :certname (constantly example-certname))
                              (update :producer_timestamp time/to-string))

        plan-report-ts (-> 1 time/days time/ago time/to-string)
        test-report (tu/change-report-time example-report (time/unparse (time/formatters :date-time) current-time))
        plan-report (assoc test-report :type "plan" :producer_timestamp (time/to-string plan-report-ts))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace catalog" cmd-consts/latest-catalog-version example-catalog)
       (let [report-with-duplicate-events (update-in test-report
                                                     [:resources 0 :events]
                                                     (fn [events] (conj events (first events))))]
         ;; this will add a duplicate event into a report, which will then be dropped at the database
         ;; side
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "store report" cmd-consts/latest-report-version report-with-duplicate-events)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "store report" cmd-consts/latest-report-version plan-report))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace facts" cmd-consts/latest-facts-version example-facts)

       ;; ensure the three combinations of fact expiration - false, true and not set
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "configure expiration"
                                    cmd-consts/latest-configure-expiration-version
                                    example-configure-expiration-false)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname2
                                    "configure expiration"
                                    cmd-consts/latest-configure-expiration-version
                                    example-configure-expiration-true)
       (scf-storage/maybe-activate-node! "i_dont_have_an_expiration_setting" (time/now))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace catalog inputs"
                                    cmd-consts/latest-catalog-inputs-version
                                    catalog-input-cmd)
       (is (= catalog-input-cmd
              (-> (svc-utils/pdb-cmd-url)
                  svc-utils/get-all-catalog-inputs
                  first)))
       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (let [expected [(tur/update-report-pe-fields test-report)
                       (tur/update-report-pe-fields plan-report)]
             actual (filter-reports [:and
                                     [:= :certname example-certname]
                                     [:null? :type false]])]
         (is (= (count expected) (count actual)))
         (is (= (set expected) (set actual))))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (export/export! export-out-file query-fn))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (let [dispatcher (tk-app/get-service svc-utils/*server*
                                            :PuppetDBCommandDispatcher)
             submit-command-fn (partial command/enqueue-command dispatcher)]
         (import/import! export-out-file submit-command-fn))

       @(tu/block-until-results 200 (first (get-catalogs example-certname)))
       @(tu/block-until-results 200 (first (get-reports example-certname)))
       @(tu/block-until-results 200 (first (get-factsets example-certname)))

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (let [expected [(tur/update-report-pe-fields test-report)
                       (tur/update-report-pe-fields plan-report)]
             actual (filter-reports [:and
                                     [:= :certname example-certname]
                                     [:null? :type false]])]
         (is (= (count expected) (count actual)))
         (is (= (set expected) (set actual))))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (let [nodes (get-nodes :include-facts-expiration true)]
         (is (= 2 (count nodes)))
         (is (= (sort-by :certname example-nodes)
                (sort-by :certname
                         (map #(select-keys % [:certname :expires_facts :expires_facts_updated])
                              nodes)))))

       (is (= catalog-input-cmd
              (-> (svc-utils/pdb-cmd-url)
                  svc-utils/get-all-catalog-inputs
                  first)))))))

(deftest test-basic-roundtrip-with-expired-events
  (tur/with-corrective-change
    (let [current-time (time/to-string (time/now))
          test-report (assoc example-report :producer_timestamp current-time)]

      (svc-utils/call-with-single-quiet-pdb-instance
        (fn []
          ; reports in example/reports have old timestamps, we only update one event so it won't be filtered out
          (let [current-resource-event (assoc (get-in test-report [:resources 0 :events 0]) :timestamp current-time)
                report-with-only-one-current-event (update-in test-report
                                                        [:resources 0 :events]
                                                        (fn [events] (conj events current-resource-event)))]
           (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                        "store report" cmd-consts/latest-report-version report-with-only-one-current-event)

           (is (= 1 (count (get-in (get-reports example-certname) [0 :resources 0 :events]))))
           (is (= [current-resource-event]
                  (get-in (get-reports example-certname) [0 :resources 0 :events])))))))))

(deftest test-anonymized-export
  (doseq [profile (keys anon/anon-profiles)]
    (let [export-out-file (tu/temp-file "export-test" ".tar.gz")
          anon-out-file (tu/temp-file "anon-test" ".tar.gz")
          current-time (time/now)
          test-report (tu/change-report-time example-report (time/unparse (time/formatters :date-time) current-time))]

      (svc-utils/call-with-single-quiet-pdb-instance
       (fn []
         (is (empty? (get-nodes)))

         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace catalog" cmd-consts/latest-catalog-version example-catalog)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "store report" cmd-consts/latest-report-version test-report)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace facts" cmd-consts/latest-facts-version example-facts)

         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace catalog inputs"
                                      cmd-consts/latest-catalog-inputs-version
                                      (-> (sample-input-cmds)
                                          (get "host-1")
                                          (update :certname (constantly example-certname))
                                          (update :producer_timestamp time/to-string)))

         (is (= (tuc/munge-catalog example-catalog)
                (tuc/munge-catalog (get-catalogs example-certname))))
         (is (= [(tur/update-report-pe-fields test-report)]
                (get-reports example-certname)))
         (is (= (tuf/munge-facts example-facts)
                (tuf/munge-facts (get-factsets example-certname))))

         (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
           (export/export! export-out-file query-fn)
           (export/export! anon-out-file query-fn profile)
           (let [export-out-map (munge-tar-map (tar->map export-out-file))
                 anon-out-map (munge-tar-map (tar->map anon-out-file))]
             (if (= profile "none")
               (is (= export-out-map anon-out-map))
               (is (not= export-out-map anon-out-map))))))))))

(deftest test-sample-statistics
  (svc-utils/call-with-single-quiet-pdb-instance
    (fn []
      (let [example-catalog2 (-> (get-in examples/wire-catalogs [9 :basic])
                                 (assoc :certname "bar.com"))
            example-facts2 (-> example-facts
                               (dissoc-in [:values :baz])
                               (assoc-in [:values :spam] "eggs")
                               (assoc :certname "bar.com"))]
        (is (empty? (get-nodes)))

        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                     "replace catalog" cmd-consts/latest-catalog-version
                                     example-catalog)
        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "bar.com"
                                     "replace catalog" cmd-consts/latest-catalog-version
                                     example-catalog2)

        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                     "store report" cmd-consts/latest-report-version
                                     example-report)
        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                     "replace facts" cmd-consts/latest-facts-version
                                     example-facts)
        (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "bar.com"
                                     "replace facts" cmd-consts/latest-facts-version
                                     example-facts2)

        (sutils/vacuum-analyze *db*)

        (let [summary-stats (get-summary-stats)
              quantile-fields [:num_resources_per_file
                               :report_log_size_dist
                               :report_metric_size_dist
                               :file_resources_per_catalog
                               :file_resources_per_catalog_with_source]]
          (doseq [f quantile-fields]
            (let [quantiles (:quantiles (first (f summary-stats)))]
              (testing (format "metric %s has 21 quantiles" f)
                (is (= 21 (count quantiles))))
              (testing (format "metric %s quantiles increasing" f)
                (is (= quantiles (sort quantiles))))
              (testing (format "metric %s contains no nils" f)
                (is (every? (complement nil?) quantiles))))))))))
