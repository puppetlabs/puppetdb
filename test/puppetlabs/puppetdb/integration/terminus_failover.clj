(ns puppetlabs.puppetdb.integration.terminus-failover
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [slingshot.test]))

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

(deftest ^:integration soft-write-fail
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {:terminus {:main {:soft_write_failure true}}})]
    (tk-app/stop (-> pdb int/server-info :app))

    (testing "Agent run should fail for manifest which collects resources"
      (is (thrown+? (and (= (:kind %) ::int/bundle-exec-failure)
                         (re-find #"Could not retrieve resources from the PuppetDB"
                                  (get-in % [:result :err])))
            (int/run-puppet ps pdb "Notify <<| |>>"))))

    (testing "Agent run should succeed for manifest which exports resources"
      (int/run-puppet ps pdb "@@notify { 'exported notify': }"))))
