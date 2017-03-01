(ns puppetlabs.puppetdb.integration.storeconfigs.deactivated-nodes
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [me.raynes.fs :as fs]))

(deftest ^:integration deactivated-nodes
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (let [exporter-manifest  "@@notify { 'Hello from exporter': }"
          test-collection (fn test-collection [should-be-active]
                            (let [{agent-stdout :out} (int/run-puppet-as "collector" ps pdb "Notify <<| |>>")
                                  {status-stdout :out} (int/run-puppet-node-status pdb "exporter") ]
                              (if should-be-active
                                (do
                                  (is (re-find #"Hello from exporter" agent-stdout))
                                  (is (re-find #"Currently active" status-stdout)))
                                (do
                                  (is (not (re-find #"Hello from exporter" agent-stdout)))
                                  (is (re-find #"Deactivated at" status-stdout))))))]

      (testing "Resources should be collected before deactivation"
        (int/run-puppet-as "exporter" ps pdb exporter-manifest)
        (test-collection true))

      (testing "Resources from deactivated nodes should be ignored"
        (int/run-puppet-node-deactivate pdb "exporter")
        (test-collection false))

      (testing "Resources from reactivated nodes should be collected"
        (int/run-puppet-as "exporter" ps pdb exporter-manifest)
        (test-collection true)))))
