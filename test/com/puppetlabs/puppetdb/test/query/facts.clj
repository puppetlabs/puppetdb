(ns com.puppetlabs.puppetdb.test.query.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.query.facts :as facts])
  (:use clojure.test
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(defn- raw-query-facts
  [query paging-options]
  (-> (facts/query->sql query)
      (facts/query-facts paging-options)))

(defn- query-facts
  [paging-options]
  (:result (raw-query-facts nil paging-options)))

(deftest facts-for-node
  (let [certname "some_certname"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (scf-store/add-certname! certname)
    (scf-store/add-facts! certname facts (now))
    (testing "with facts present for a node"
       (is (= (facts/facts-for-node certname) facts)))
    (testing "without facts present for a node"
       (is (= (facts/facts-for-node "imaginary_node") {})))
    (testing "after deleting facts for a node"
      (scf-store/delete-facts! certname)
      (is (= (facts/facts-for-node certname) {})))))

(deftest paging-results
  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host"}
        f2         {:certname "b.local" :name "uptime_days" :value "4"}
        f3         {:certname "c.local" :name "hostname"    :value "c-host"}
        f4         {:certname "d.local" :name "uptime_days" :value "2"}
        fact-count 4]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! "c.local" {"hostname" "c-host"} (now))
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! "a.local" {"hostname" "a-host"} (now))
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! "d.local" {"uptime_days" "2"} (now))
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! "b.local" {"uptime_days" "4"} (now))

    (testing "include total results count"
      (let [actual (:count (raw-query-facts nil {:count? true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-facts {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
              (query-facts {:order-by [{:field :invalid-field}]}))))

      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC"  [f1 f2 f3 f4]]
                                  ["DESC" [f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-facts {:order-by [{:field :certname :order order}]})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[name-order value-order] expected] [[["DESC" "ASC"]  [f4 f2 f1 f3]]
                                                     [["DESC" "DESC"] [f2 f4 f3 f1]]
                                                     [["ASC" "DESC"]  [f3 f1 f2 f4]]
                                                     [["ASC" "ASC"]   [f1 f3 f4 f2]]]]
          (testing (format "name %s value %s" name-order value-order)
            (let [actual (query-facts {:order-by [{:field :name :order name-order}
                                                  {:field :value :order value-order}]})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [f1 f2 f3 f4]]
                                                   [1 [f2 f3 f4]]
                                                   [2 [f3 f4]]
                                                   [3 [f4]]
                                                   [4 []]]]
                                          ["DESC" [[0 [f4 f3 f2 f1]]
                                                   [1 [f3 f2 f1]]
                                                   [2 [f2 f1]]
                                                   [3 [f1]]
                                                   [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-facts {:order-by [{:field :certname :order order}] :offset offset})]
              (is (= actual expected)))))))))
