(ns puppetlabs.puppetdb.admin-test
  (:require [clj-time.coerce :as tc]
            [clj-time.core :refer [now]]
            [clojure.test :refer :all]
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
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [sync-command-post]]))

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
              quantile-fields [:string_fact_value_bytes
                               :structured_fact_value_bytes
                               :num_associated_factsets_over_fact_paths
                               :num_resources_per_file
                               :fact_path_sharing
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

(deftest test-fact-path-stats
  (svc-utils/call-with-single-quiet-pdb-instance
   (fn []
     (let [shared-paths #(let [v (:num_shared_fact_paths %)
                               row (first v)]
                           (is (= 1 (count v)))
                           (is (= #{:count} (-> row keys set)))
                           (:count row))
           unshared-paths #(let [v (:num_unshared_fact_paths %)
                                 row (first v)]
                             (is (= 1 (count v)))
                             (is (= #{:count} (-> row keys set)))
                             (:count row))
           sharing #(let [v (:fact_path_sharing %)
                          row (first v)]
                      (is (= 1 (count v)))
                      (is (= #{:quantiles} (-> row keys set)))
                      (let [quantiles (:quantiles row)]
                        (when (seq quantiles)
                          (= 21 (count quantiles)))
                        (doseq [q quantiles]
                          (is (number? q)))
                        quantiles))
           replace-facts (fn [certname facts]
                           (sync-command-post
                            (svc-utils/pdb-cmd-url)
                            certname
                            "replace facts"
                            cmd-consts/latest-facts-version
                            {:certname certname
                             :environment "DEV"
                             :values facts
                             :producer_timestamp (tc/to-string (now))
                             :producer "irrelevant"}))]

       (let [stats (get-summary-stats)]
         (is (zero? (shared-paths stats)))
         (is (zero? (unshared-paths stats)))
         (doseq [quantile (sharing stats)]
           ;; All the values should be approximately 0
           (is (< (Math/abs (- 0.0 quantile))
                  0.01))))

       (replace-facts "host-1" {:x 1})
       (replace-facts "host-2" {:x 2})
       (let [stats (get-summary-stats)]
         (is (= 1 (shared-paths stats)))
         (is (zero? (unshared-paths stats)))
         (doseq [quantile (sharing stats)]
           ;; All the values should be approximately 2
           (is (< (Math/abs (- 2.0 quantile))
                  0.01))))

       (replace-facts "host-1" {:x 1})
       (replace-facts "host-2" {:y 2})
       (let [stats (get-summary-stats)]
         (is (zero? (shared-paths stats)))
         (is (= 2 (unshared-paths stats)))
         (doseq [quantile (sharing stats)]
           (is (< (Math/abs (- 1.0 quantile))
                  0.01))))

       ;; For 21 fact paths, create 21 hosts, each with one less path
       ;; than the previous so that we should have 20 shared paths,
       ;; one unshared path, and quantiles that are roughly linear
       ;; from 1 to 20.
       (doseq [hn (range 21)]
         (replace-facts (str "host-" hn)
                        (into {}
                              (for [pn (range hn 21)]
                                [(keyword (str "p-" pn)) "value!"]))))

       (let [stats (get-summary-stats)]
         (is (= 20 (shared-paths stats)))
         (is (= 1 (unshared-paths stats)))
         (doall (map-indexed (fn [i quantile]
                               (let [expected (inc i)]
                                 (is (< (Math/abs (- expected quantile))
                                        0.01))))
                             (sharing stats))))))))
