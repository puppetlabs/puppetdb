(ns puppetlabs.puppetdb.integration.soft-fail.soft-write-fail
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]))

(deftest ^:integration soft-write-fail
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {:terminus {:main {:soft_write_failure true}}})]
    (tk-app/stop (-> pdb int/info-map :app))

    (testing "Agent run should fail for manifest which collects resources"
      (try
        (int/run-puppet ps pdb "Notify <<| |>>")
        (is false "The puppet run should have thrown an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"Could not retrieve resources from the PuppetDB"
                       (-> e .getData :err))))))

    (testing "Agent run should succeed for manifest which exports resources"
      (try
        (int/run-puppet ps pdb "@@notify { 'exported notify': }"
                        {:timeout 500})
        (catch clojure.lang.ExceptionInfo e
          (is (:timeout-ms (.getData e))))))))

