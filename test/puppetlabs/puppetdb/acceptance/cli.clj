(ns puppetlabs.puppetdb.acceptance.cli
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.cli.export :as cli-export]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.puppetdb.cli.anonymize :as cli-anon]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils.cli :refer :all]
            [puppetlabs.puppetdb.testutils.tar :refer [tar->map]]
            [puppetlabs.puppetdb.fixtures :as fixt]))

(deftest test-tarball-anonymization-roundtrip
  (for [profile (keys anon/anon-profiles)]
    (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))
          anon-out-file (.getPath (tu/temp-file "anon-test" ".tar.gz"))]
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

         (#'cli-export/-main "--outfile" export-out-file
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))
         (#'cli-anon/-main "--outfile" anon-out-file
                           "--infile" export-out-file
                           "--profile" profile)

         (let [export-out-map (munge-tar-map (tar->map export-out-file))
               anon-out-map (munge-tar-map (tar->map anon-out-file))]
           (is (not= export-out-map anon-out-map)))))

      (svc-utils/call-with-single-quiet-pdb-instance
       (let [anon-out-map (tar->map anon-out-file)
             anon-certname (-> anon-out-map (get "reports") first val (get "certname"))]
         (fn []
           (is (empty? (get-nodes)))

           (#'cli-import/-main "--infile" anon-out-file
                               "--host" (:host svc-utils/*base-url*)
                               "--port" (str (:port svc-utils/*base-url*)))

           @(tu/block-until-results 100 (first (get-catalogs anon-certname)))
           @(tu/block-until-results 100 (first (get-reports anon-certname)))
           @(tu/block-until-results 100 (first (get-factsets anon-certname)))

           (is (some? (get-catalogs anon-certname)))
           (is (some? (get-reports anon-certname)))
           (is (some? (get-factsets anon-certname)))))))))

(deftest test-anonymized-roundtrip
  (for [profile (keys anon/anon-profiles)]
    (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))
          anon-out-file (.getPath (tu/temp-file "anon-test" ".tar.gz"))]
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

         (#'cli-export/-main "--outfile" export-out-file
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))
         (#'cli-anon/-main "--outfile" anon-out-file
                           "--infile" export-out-file
                           "--profile" profile)

         (let [export-out-map (tar->map export-out-file)
               anon-out-map (tar->map anon-out-file)]
           (is (not= export-out-map anon-out-map)))))

      (svc-utils/call-with-single-quiet-pdb-instance
       (let [anon-out-map (tar->map anon-out-file)
             anon-certname (-> anon-out-map (get "reports") first val (get "certname"))]
         (fn []
           (is (empty? (get-nodes)))

           (#'cli-import/-main "--infile" anon-out-file
                               "--host" (:host svc-utils/*base-url*)
                               "--port" (str (:port svc-utils/*base-url*)))

           @(tu/block-until-results 100 (first (get-catalogs anon-certname)))
           @(tu/block-until-results 100 (first (get-reports anon-certname)))
           @(tu/block-until-results 100 (first (get-factsets anon-certname)))

           (is (some? (get-catalogs anon-certname)))
           (is (some? (get-reports anon-certname)))
           (is (some? (get-factsets anon-certname)))))))))

(deftest test-anonymized-export-roundtrip
  (for [profile (keys anon/anon-profiles)]
    (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))
          anon-out-file (.getPath (tu/temp-file "anon-test" ".tar.gz"))]
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

         (#'cli-export/-main "--outfile" export-out-file
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))

         (#'cli-export/-main "--outfile" anon-out-file
                             "--anonymization" profile
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))

         (let [export-out-map (munge-tar-map (tar->map export-out-file))
               anon-out-map (munge-tar-map (tar->map anon-out-file))]
           (is (not= export-out-map anon-out-map)))))

      (svc-utils/call-with-single-quiet-pdb-instance
       (let [anon-out-map (tar->map anon-out-file)
             anon-certname (-> anon-out-map (get "reports") first val (get "certname"))]
         (fn []
           (is (empty? (get-nodes)))

           (#'cli-import/-main "--infile" anon-out-file
                               "--host" (:host svc-utils/*base-url*)
                               "--port" (str (:port svc-utils/*base-url*)))

           @(tu/block-until-results 100 (first (get-catalogs anon-certname)))
           @(tu/block-until-results 100 (first (get-reports anon-certname)))
           @(tu/block-until-results 100 (first (get-factsets anon-certname)))

           (is (some? (get-catalogs anon-certname)))
           (is (some? (get-reports anon-certname)))
           (is (some? (get-factsets anon-certname)))))))))
