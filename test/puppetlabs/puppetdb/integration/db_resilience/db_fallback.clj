(ns puppetlabs.puppetdb.integration.db-resilience.db-fallback
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]))

(deftest ^:integration db-fallback
  (with-open [pg1 (int/setup-postgres)
              pg2 (int/setup-postgres)
              pdb1 (int/run-puppetdb pg1 {})
              pdb2 (int/run-puppetdb pg2 {})
              ps (int/run-puppet-server [pdb1 pdb2] {})]

    (testing "Agent run against pdb1"
      (int/run-puppet ps "notify { 'hello, world!': }")
      (is (= 1 (count (int/pql-query pdb1 "nodes {}")))))

    (testing "Fallback to pdb2"
      (tk-app/stop (:app pdb1))
      (is (= 0 (count (int/pql-query pdb2 "nodes {}"))))

      (int/run-puppet ps "notify { 'hello, world!': }")
      (is (= 1 (count (int/pql-query pdb2 "nodes {}")))))))
