(ns puppetlabs.puppetdb.integration.inventory.basic-fact-retrieval
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]))

(def structured-data
  {"foo" [1 2 3]
   "bar" {"f" [3.14 2.71]
          "*" "**"
          "#~" ""},
   "baz" [{"a" 1}
          {"b" 2}]})

(deftest ^:integration basic-fact-retrieval
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

      (testing "Create an custom fact with a null byte"
        (int/run-puppet ps pdb "notify { 'irrelevant manifest': }"
                        {:certname "null-fact-agent"
                         :env {"FACTER_nullfact"
                               "{\"nullfact\": \"foo\\u0000bar\"}" }}))

      (testing "Ensure that the null fact is passed through properly"
        (is (= 1
               (-> (int/pql-query pdb "facts { name = 'nullfact' }")
                   count)))))))
