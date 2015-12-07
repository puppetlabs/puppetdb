(ns puppetlabs.puppetdb.http.index-test
  (:require [clj-time.core :refer [now]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.testutils.http :refer [deftest-http-app
                                                        ordered-query-result
                                                        query-response
                                                        query-result
                                                        vector-param]]))

(def endpoints [[:v4 "/v4"]])

(deftest-http-app index-queries
  [[version endpoint] endpoints
   method [:get :post]]
  (let [catalog (:basic catalogs)
        facts   {"kernel"          "Linux"
                 "operatingsystem" "Debian"}
        facts1  (assoc facts "fqdn" "host1")
        facts2  (assoc facts "fqdn" "host2")
        facts3  (assoc facts "fqdn" "host3")
        cat1    (assoc catalog :certname "host1")
        cat2    (assoc catalog :certname "host2")
        cat3    (assoc catalog :certname "host3")]
    (scf-store/add-certname! "host1")
    (scf-store/add-certname! "host2")
    (scf-store/add-certname! "host3")
    (scf-store/replace-catalog! cat1 (now))
    (scf-store/replace-catalog! cat2 (now))
    (scf-store/replace-catalog! cat3 (now))
    (scf-store/add-facts! {:certname "host1"
                           :values facts1
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname "host2"
                           :values facts2
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname "host3"
                           :values facts3
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/deactivate-node! "host3")

    (testing "invalid from query"
      (let [{:keys [status body]} (query-response method endpoint ["from" "foobar"])]
        (is (re-find #"Invalid entity" body))
        (is (= status http/status-bad-request))))

    (testing "pagination"
      (testing "with order_by only"
        (let [results (ordered-query-result method endpoint ["from" "nodes"]
                                            {:order_by
                                             (vector-param method
                                                           [{"field" "certname"
                                                             "order" "ASC"}])})]
          (is (= "host1" (:certname (first results))))
          (is (= 3 (count results)))))

      (testing "with all options"
        (let [results (ordered-query-result method endpoint ["from" "nodes"]
                                            {:order_by
                                             (vector-param method
                                                           [{"field" "certname"
                                                             "order" "DESC"}])
                                             :limit 2
                                             :offset 1})]
          (is (= "host2" (:certname (first results))))
          (is (= 2 (count results))))))

    (testing "extract parameters"
      (let [results (query-result method endpoint ["from" "nodes"
                                                   ["extract" "certname"
                                                    ["=" "certname" "host2"]]])]
        (is (= results #{{:certname "host2"}}))))

    (testing "nodes"
      (testing "query should return all nodes (including deactivated ones)"
        (is (= (set (mapv :certname (query-result method endpoint ["from" "nodes"] {})))
               #{"host1" "host2" "host3"})))

      (testing "query should return single node info"
        (doseq [host ["host1" "host2" "host3"]]
          (let [results (query-result method endpoint ["from" "nodes" ["=" "certname" host]])
                result (first results)]
            (is (= host (:certname result)))
            (if (= host "host3")
              (is (:deactivated result))
              (is (nil? (:deactivated result))))))))

    (testing "resources"
      (testing "query should return the resources just for that node"
        (doseq [host ["host1" "host2"]]
          (let [results (query-result method endpoint ["from" "resources" ["=" "certname" host]])]
            (is (= (set (map :certname results)) #{host})))))

      (testing "query should return the resources just for that node matching the supplied type"
        (doseq [host ["host1" "host2"]]
          (let [results (query-result method endpoint ["from" "resources"
                                                       ["and"
                                                        ["=" "certname" host]
                                                        ["=" "type" "File"]]])]
            (is (= (set (map :certname results)) #{host}))
            (is (= (set (map :type results)) #{"File"}))
            (is (= (count results) 2)))))

      (testing "query should return all resources matching the supplied type"
        (let [results (query-result method endpoint ["from" "resources" ["=" "type" "File"]])]
          (is (= (set (map :certname results)) #{"host1" "host2" "host3"}))
          (is (= (set (map :type results)) #{"File"}))
          (is (= (count results) 6))))

      (testing "query should return [] if the <type> doesn't match anything"
        (let [results (query-result method endpoint ["from" "resources" ["=" "type" "Foobar"]])]
          (is (= results #{})))))

    (testing "facts"
      (testing "query should return all instances of the given fact"
        (let [results (query-result method endpoint ["from" "facts" ["=" "name" "kernel"]])]
          (is (= (set (map :name results)) #{"kernel"}))
          (is (= (count results) 3))))

      (testing "query should return all instances of the given fact with the given value"
        (let [results (query-result method endpoint ["from" "facts" ["and" ["=" "name" "kernel"] ["=" "value" "Linux"]]])]
          (is (= (set (map :name results)) #{"kernel"}))
          (is (= (set (map :value results)) #{"Linux"}))
          (is (= (count results) 3))))

      (testing "query should return [] if the fact doesn't match anything"
        (let [results (query-result method endpoint ["from" "facts" ["=" "name" "blah"]])]
          (is (= results #{})))))

    (testing "fact_contents query should match expected results"
      (let [results (query-result method endpoint ["from" "fact_contents" ["=" "certname" "host1"]])]
        (is (= (set (mapv :name results))
               #{"kernel" "operatingsystem" "fqdn"}))))))
