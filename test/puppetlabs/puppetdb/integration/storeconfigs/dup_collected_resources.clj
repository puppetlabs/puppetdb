(ns puppetlabs.puppetdb.integration.storeconfigs.dup-collected-resources
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]))

(deftest ^:integration dup-collected-resources
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (testing "Run puppet on exporters to create duplicate exported resources"
      (doseq [certname ["exporter1" "exporter2"]]
        (int/run-puppet-as certname ps pdb "@@notify { 'DUPE NOTIFY': }")))

    (testing "Run puppet on collector and expect failure"
      (try
        (int/run-puppet-as "collector" ps pdb "Notify <<| title == 'DUPE NOTIFY' |>>")
        (is false "The collector puppet run should have thrown an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (re-find #"duplicate resource was found while collecting exported resources"
                       (-> e .getData :err))))))))

