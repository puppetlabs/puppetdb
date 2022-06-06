(ns puppetlabs.puppetdb.integration.masterless
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.integration.fixtures :as int]))

(deftest ^:integration masterless-fact-storage
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})]
    (testing "Run puppet apply to populate the db"
      (let [{:keys [out]} (int/run-puppet-apply pdb "notice($foo)"
                                                {:env {"FACTER_foo" "testfoo"}})]
        (is (re-find #"testfoo" out))))

    (testing "Run again to ensure we aren't using puppetdb for the answer"
      (let [{:keys [out]} (int/run-puppet-apply pdb "notice($foo)"
                                                {:env {"FACTER_foo" "second test"}})]
        (is (re-find #"second test" out))))

    (testing "Check that the fact was put in puppetdb"
      (is (= [{:value "second test"}] (int/pql-query pdb "facts [value] { name='foo' }"))))))
