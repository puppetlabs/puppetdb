(ns puppetlabs.puppetdb.integration.db-resilience.db-fallback
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]))

(deftest ^:integration db-fallback
  (with-open [pg1 (int/setup-postgres)
              pg2 (int/setup-postgres)
              pdb1 (int/run-puppetdb pg1 {})
              pdb2 (int/run-puppetdb pg2 {})
              ps (int/run-puppet-server [pdb1 pdb2] {})]

    (testing "Agent run against pdb1"
      (int/run-puppet ps pdb1 "notify { 'initial': }")
      (is (= 1 (count (int/pql-query pdb1 "resources { title='initial' }"))))
      (is (= 0 (count (int/pql-query pdb2 "resources { title='initial' }")))))

    (testing "Fallback to pdb2"
      (tk-app/stop (:app pdb1))
      (int/run-puppet ps pdb2 "notify { 'fallback': }")
      (is (= 1 (count (int/pql-query pdb2 "resources { title='fallback' }")))))

    (testing "Restore pdb1"
      (tk-app/start (:app pdb1))
      (int/run-puppet ps pdb1 "notify { 'restored': }")
      (is (= 1 (count (int/pql-query pdb1 "resources { title='restored' }"))))
      (is (= 0 (count (int/pql-query pdb2 "resources { title='restored' }")))))))
