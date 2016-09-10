(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.cli.export :as cli-export]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.puppetdb.command.constants :as cmd-consts]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils.cli
             :refer [get-nodes get-catalogs get-factsets get-reports munge-tar-map
                     example-catalog example-report example-facts example-certname]]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]))

(use-fixtures :each tu/call-with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))]
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

       (#'cli-export/-main "--outfile" export-out-file
                           "--host" (:host svc-utils/*base-url*)
                           "--port" (str (:port svc-utils/*base-url*)))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (#'cli-import/-main "--infile" export-out-file
                           "--host" (:host svc-utils/*base-url*)
                           "--port" (str (:port svc-utils/*base-url*)))

       @(tu/block-until-results 200 (first (get-catalogs example-certname)))
       @(tu/block-until-results 200 (first (get-reports example-certname)))
       @(tu/block-until-results 200 (first (get-factsets example-certname)))

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= [(tur/update-report-pe-fields example-report)]
              (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))))))

(deftest test-facts-only-roundtrip
  (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                    "replace facts" cmd-consts/latest-facts-version example-facts)

       (is (empty? (get-catalogs example-certname)))
       (is (empty? (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (#'cli-export/-main "--outfile" export-out-file
                           "--host" (:host svc-utils/*base-url*)
                           "--port" (str (:port svc-utils/*base-url*)))))

    (with-test-db
      (svc-utils/call-with-single-quiet-pdb-instance
       (assoc (svc-utils/create-temp-config)
              :database *db*)

       (fn []
         (is (empty? (get-nodes)))

         (#'cli-import/-main "--infile" export-out-file
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))

         @(tu/block-until-results 200 (first (get-factsets example-certname)))

         (is (empty? (get-catalogs example-certname)))
         (is (empty? (get-reports example-certname)))
         (is (= (tuf/munge-facts example-facts)
                (tuf/munge-facts (get-factsets example-certname)))))))))
