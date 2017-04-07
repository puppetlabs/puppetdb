(ns puppetlabs.puppetdb.integration.inventory
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]
            [me.raynes.fs :as fs]))

(def structured-data
  {"foo" [1 2 3]
   "bar" {"f" [3.14 2.71]
          "*" "**"
          "#~" ""}
   "baz" [{"a" 1}
          {"b" 2}]})

(deftest ^:integration inventory-test
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})] 
    (let [agents ["fact-agent-1" "fact-agent-2"]]
      (testing "Run agent once to populate database"
        (doseq [a agents]
          (int/run-puppet-as a ps pdb "notify { 'irrelevant manifest': }")))

      (testing "Run facts face to find facts for each node"
        (doseq [a agents]
          (let [facts (int/run-puppet-facts-find ps a)]
            (is (= a (get facts "name"))))))

      (testing "Query the database for trusted facts"
        (doseq [agent-facts (int/pql-query pdb "facts { name = 'trusted' }")]
          (is (= "remote" (get-in agent-facts [:value :authenticated])))))

      (testing "Create a custom structured fact"
        (int/run-puppet ps pdb "notify { 'irrelevant manifest': }"
                        {:certname "structured-fact-agent"
                         :env {"FACTER_my_structured_fact" (json/generate-string structured-data)}}))

      (testing "Ensure that the structured fact is passed through properly"
        (is (= structured-data
               (-> (int/pql-query pdb "facts { name = 'my_structured_fact' }")
                   first
                   (get :value)
                   json/parse-string))))

      (testing "Send a package inventory fact from the agent"
        (let [temp-facterlib (fs/temp-dir "pdb-test-facterlib")
              custom-fact (str temp-facterlib "/packages.rb")]
          (spit custom-fact
                (str "Facter.add(:_puppet_inventory_1) do "
                     "  setcode do "
                     "    { 'packages' => [['openssl', '1.0.2g-1ubuntu4.6', 'apt']] }"
                     "  end "
                     "end"))

          (int/run-puppet ps pdb
                          "notify { 'irrelevant manifest': }"
                          {:certname "agent-with-packages"
                           :env {"FACTERLIB" temp-facterlib}})))
      (testing "Ensure that facts were stored"
        (is (pos? (count (int/pql-query pdb "facts { }")))))

      (testing "Ensure the inventory fact was removed"
        (is (= 0 (count (int/pql-query pdb "facts { name = '_puppet_inventory_1' }")))))


      (testing "Package inventory can be queried"
        (is (= [{:package_name "openssl"
                 :version "1.0.2g-1ubuntu4.6"
                 :provider "apt"}]
               (int/pql-query pdb "packages { package_name = 'openssl' }")))

        (is (= [{:package_name "openssl"
                 :version "1.0.2g-1ubuntu4.6"
                 :provider "apt"
                 :certname "agent-with-packages"}]
               (int/pql-query pdb "package_inventory { certname = 'agent-with-packages' }")))))))
