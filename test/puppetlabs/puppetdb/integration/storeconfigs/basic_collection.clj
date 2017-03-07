(ns puppetlabs.puppetdb.integration.storeconfigs.basic-collection
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]))

(defn manifest-for [certname]
  (str "@@notify { 'Hello from " certname "': }\n"
       "Notify <<| |>>"))

(deftest ^:integration basic-collection
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [agents ["agent-1" "agent-2"]]
      (testing "Run with an exported resource first"
        (doseq [agent agents]
          (int/run-puppet-as agent ps pdb (manifest-for agent))))

      (testing "Then run again, and check each agent sees all the others"
        (doseq [agent agents]
          (let [{:keys [out]} (int/run-puppet-as agent ps pdb (manifest-for agent))]
            (doseq [expected-agent agents]
              (is (re-find (re-pattern (str "Notice: Hello from " expected-agent))
                           out))))))

      (testing "attempt export with undef array elements"
        ;; just see that it doesn't throw
        (int/run-puppet-as "agent-1" ps pdb
                           "@@notify { 'test': tag => [undef, 'a', 'b'] }")))))
