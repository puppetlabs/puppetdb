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
                     example-facts example-certname
                     get-summary-stats]]
            [puppetlabs.puppetdb.testutils.tar :refer [tar->map]]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(use-fixtures :each tu/call-with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [export-out-file (tu/temp-file "export-test" ".tar.gz")]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace catalog" cmd-consts/latest-catalog-version example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "store report" cmd-consts/latest-report-version example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace facts" cmd-consts/latest-facts-version example-facts)

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= [(tur/update-report-pe-fields example-report)]
              (get-reports example-certname)))
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
       (is (= [(tur/update-report-pe-fields example-report)]
              (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))))))

(deftest test-anonymized-export
  (doseq [profile (keys anon/anon-profiles)]
    (let [export-out-file (tu/temp-file "export-test" ".tar.gz")
          anon-out-file (tu/temp-file "anon-test" ".tar.gz")]

      (svc-utils/call-with-single-quiet-pdb-instance
       (fn []
         (is (empty? (get-nodes)))

         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace catalog" cmd-consts/latest-catalog-version example-catalog)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "store report" cmd-consts/latest-report-version example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace facts" cmd-consts/latest-facts-version example-facts)

         (is (= (tuc/munge-catalog example-catalog)
                (tuc/munge-catalog (get-catalogs example-certname))))
         (is (= [(tur/update-report-pe-fields example-report)]
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
                (every? (complement nil?) quantiles)))))))))
