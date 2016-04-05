(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.client :as pdb-client]
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

(deftest test-max-frame-size
  (with-test-db
    (svc-utils/call-with-single-quiet-pdb-instance
     (-> (svc-utils/create-temp-config)
         (assoc :database *db*)
         (assoc-in [:command-processing :max-frame-size] 1024))
     (fn []
       (is (empty? (get-nodes)))

       (pdb-client/submit-command-via-http! (svc-utils/pdb-cmd-url)
                                            example-certname
                                            "replace catalog"
                                            7
                                            example-catalog)

       (is (thrown-with-msg?
            java.util.concurrent.ExecutionException #"Results not found"
            @(tu/block-until-results 5 (first (get-catalogs example-certname)))))))))
