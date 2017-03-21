(ns puppetlabs.puppetdb.integration.inventory.package-inventory
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]
            [me.raynes.fs :as fs]))

(deftest ^:integration package-inventory
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (testing "Run agent to send the package inventory fact"

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

    ;; uncomment this once query support gets merged
    (comment
      (testing "Package inventory can be queried"
        (is (= [{:package_name "openssl"
                 :version "1.0.2g-1ubuntu4.6"
                 :provider "apt"}]
               (int/pql-query pdb "packages { certname = 'agent-with-packages' }")))))))
