(ns puppetlabs.puppetdb.integration.soft-fail.soft-write-fail
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [slingshot.test]))

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
