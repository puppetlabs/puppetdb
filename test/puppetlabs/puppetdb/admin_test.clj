(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.command :refer [enqueue-command]]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.cli
             :refer [get-nodes get-catalogs get-factsets get-reports munge-tar-map
                     example-catalog example-report example-facts example-certname]]
            [puppetlabs.puppetdb.testutils.tar :refer [tar->map]]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(use-fixtures :each tu/call-with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [export-out-file (tu/temp-file "export-test" ".tar.gz")]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace catalog" 7 example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "store report" 6 example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace facts" 4 example-facts)

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= [example-report] (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (export/export! export-out-file query-fn))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (let [dispatcher (tk-app/get-service svc-utils/*server*
                                            :PuppetDBCommandDispatcher)
             submit-command-fn (partial enqueue-command dispatcher)
             command-versions (:command_versions (cli-import/parse-metadata
                                                  export-out-file))]
         (import/import! export-out-file command-versions submit-command-fn))

       @(tu/block-until-results 100 (first (get-catalogs example-certname)))
       @(tu/block-until-results 100 (first (get-reports example-certname)))
       @(tu/block-until-results 100 (first (get-factsets example-certname)))

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= [example-report] (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))))))

(deftest test-anonymized-export
  (doseq [profile (keys anon/anon-profiles)]
    (let [export-out-file (tu/temp-file "export-test" ".tar.gz")
          anon-out-file (tu/temp-file "anon-test" ".tar.gz")]

      (svc-utils/call-with-single-quiet-pdb-instance
       (fn []
         (is (empty? (get-nodes)))

         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace catalog" 7 example-catalog)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "store report" 6 example-report)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace facts" 4 example-facts)


         (is (= (tuc/munge-catalog example-catalog)
                (tuc/munge-catalog (get-catalogs example-certname))))
         (is (= [example-report] (get-reports example-certname)))
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
