(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.command :refer [submit-command]]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.cli :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(use-fixtures :each fixt/with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [export-out-file (tu/temp-file "export-test" ".tar.gz")]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace catalog" 6 example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "store report" 5 example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) "replace facts" 4 example-facts)

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= (tur/munge-report example-report)
              (tur/munge-report (get-reports example-certname))))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (export/export! export-out-file query-fn))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (let [dispatcher (tk-app/get-service svc-utils/*server*
                                            :PuppetDBCommandDispatcher)
             submit-command-fn (partial submit-command dispatcher)
             command-versions (:command_versions (cli-import/parse-metadata
                                                  export-out-file))]
         (import/import! export-out-file command-versions submit-command-fn))

       @(tu/block-until-results 100 (first (get-catalogs example-certname)))
       @(tu/block-until-results 100 (first (get-reports example-certname)))
       @(tu/block-until-results 100 (first (get-factsets example-certname)))

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= (tur/munge-report example-report)
              (tur/munge-report (get-reports example-certname))))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))))))
