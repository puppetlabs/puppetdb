(ns puppetlabs.puppetdb.integration.reports.producer
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(deftest ^:integration producer-populated
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server-as "my_puppetserver" [pdb] {})]
    (testing "Initial agent run, to populate puppetdb with data to query"
      (int/run-puppet-as "my_agent" ps pdb
                         (str "notify { \"hi\":"
                              "  message => \"Hi my_agent\" "
                              "}")))
    (let [result (int/entity-query pdb "/reports"
                                   ["extract" ["producer"]
                                    ["=" "certname" "my_agent"]])]
      (is (= [{:producer "my_puppetserver"}]
             result)))))
