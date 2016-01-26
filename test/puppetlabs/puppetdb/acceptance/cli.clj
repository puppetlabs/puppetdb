(ns puppetlabs.puppetdb.acceptance.cli
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.cli.export :as cli-export]
            [puppetlabs.puppetdb.cli.import :as cli-import]
            [puppetlabs.puppetdb.anonymizer :as anon]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils.cli
             :refer [get-nodes get-catalogs get-factsets get-reports munge-tar-map
                     example-catalog example-report example-facts example-certname]]
            [puppetlabs.puppetdb.testutils.tar :refer [tar->map]]))

(deftest test-anonymized-export-roundtrip
  (doseq [profile (keys anon/anon-profiles)]
    (let [export-out-file (.getPath (tu/temp-file "export-test" ".tar.gz"))
          anon-out-file (.getPath (tu/temp-file "anon-test" ".tar.gz"))]
      (svc-utils/call-with-single-quiet-pdb-instance
       (fn []
         (is (empty? (get-nodes)))

         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace catalog" 7 example-catalog)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "store report" 6 example-report)
         (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname
                                      "replace facts" 4 example-facts)

         (is (= (tuc/munge-catalog example-catalog)
                (tuc/munge-catalog (get-catalogs example-certname))))
         (is (= [example-report] (get-reports example-certname)))
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
           (if (= profile "none")
             (is (= export-out-map anon-out-map))
             (is (not= export-out-map anon-out-map))))))

      (svc-utils/call-with-single-quiet-pdb-instance
       (let [anon-out-map (tar->map anon-out-file)
             anon-certname (some-> anon-out-map (get "reports") first val (get "certname"))]
         (fn []
           (is (empty? (get-nodes)))

           (#'cli-import/-main "--infile" anon-out-file
                               "--host" (:host svc-utils/*base-url*)
                               "--port" (str (:port svc-utils/*base-url*)))

           @(tu/block-until-results 100 (first (get-catalogs anon-certname)))
           @(tu/block-until-results 100 (first (get-reports anon-certname)))
           @(tu/block-until-results 100 (first (get-factsets anon-certname)))

           (is (not (empty? (get-catalogs anon-certname))))
           (is (not (empty? (get-reports anon-certname))))
           (is (not (empty? (get-factsets anon-certname))))))))))
