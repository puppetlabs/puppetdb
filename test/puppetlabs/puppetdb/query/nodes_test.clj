(ns puppetlabs.puppetdb.query.nodes-test
  (:require [clojure.set :as set]
            [puppetlabs.puppetdb.query.nodes :as node]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [clojure.test :refer :all]
            [clj-time.core :refer [now ago days minus]]
            [clj-time.coerce :refer [to-timestamp]]
            [clojure.math.combinatorics :refer [combinations]]
            [puppetlabs.puppetdb.fixtures :refer :all]))

(use-fixtures :each with-test-db)

(defn- raw-retrieve-nodes
  [version filter-expr paging-options]
  (let [sql (node/query->sql version filter-expr paging-options)]
    (node/query-nodes version sql)))

(defn- retrieve-node-names
  ([version filter-expr] (retrieve-node-names version filter-expr {}))
  ([version filter-expr paging-options]
     (->> (raw-retrieve-nodes version filter-expr paging-options)
          (:result)
          (mapv :certname))))

(def names #{"node_a" "node_b" "node_c" "node_d" "node_e"})

(defn- combination-tests
  [versions test-cases]
  (doseq [version versions
          size (range 1 (inc (count test-cases)))
          terms (combinations test-cases size)
          :let [exprs      (map first terms)
                results    (mapv (comp set last) terms)
                and-expr   (vec (cons "and" exprs))
                and-result (apply set/intersection results)
                or-expr    (vec (cons "or" exprs))
                or-result  (apply set/union results)
                not-expr   ["not" (vec (cons "or" exprs))]
                not-result (apply set/difference names results)]]
    (testing (str "for version " version)
      (is (= (set (retrieve-node-names version and-expr))
             (set and-result))
          (format "%s => %s" and-expr and-result))
      (is (= (set (retrieve-node-names version or-expr))
             (set or-result))
          (format "%s => %s" or-expr or-result))
      (is (= (set (retrieve-node-names version not-expr))
             (set not-result))
          (format "%s => %s" not-expr not-result)))))

(deftest query-nodes
  (let [timestamp (to-timestamp (now))]
    (sql/insert-records
     :environments
     {:name "production"})

    (doseq [name names]
      (scf-store/add-certname! name))

    (scf-store/add-facts! {:name "node_a"
                           :values {"kernel" "Linux"}
                           :timestamp timestamp
                           :environment "production"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "node_b"
                           :values {"kernel" "Linux"}
                           :timestamp timestamp
                           :environment "production"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "node_c"
                           :values {"kernel" "Darwin"}
                           :timestamp timestamp
                           :environment "production"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "node_d"
                           :values {"uptime_seconds" "10000"}
                           :timestamp timestamp
                           :environment "production"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "node_e"
                           :values {"uptime_seconds" "10000"}
                           :timestamp timestamp
                           :environment "production"
                           :producer-timestamp nil})

    (testing "basic combination testing"
      (let [test-cases {["=" ["fact" "kernel"] "Linux"]
                        #{"node_a" "node_b"}
                        ["=" ["fact" "kernel"] "Darwin"]
                        #{"node_c"}
                        ["=" ["fact" "kernel"] "Nothing"]
                        #{}
                        ["=" ["fact" "uptime"] "Linux"]
                        #{}
                        ["=" ["fact" "uptime_seconds"] "10000"]
                        #{"node_d" "node_e"}}]
        (combination-tests [:v4] test-cases)))

    (testing "environment testing"
      (let [test-cases {["=" "facts_environment" "production"]
                        #{"node_a" "node_b" "node_c" "node_d" "node_e"}}]
        (combination-tests [:v4] test-cases)))))

(deftest paging-results
  (let [right-now (now)]
    (doseq [[id node facts-age catalog-age] [[1 "node_a" 1 3]
                                             [2 "node_b" 4 2]
                                             [3 "node_c" 3 1]
                                             [4 "node_d" 2 3]
                                             [5 "node_e" 5 2]]]
      (sql/insert-record :certnames {:name node})
      (sql/insert-record :factsets {:certname node :timestamp (to-timestamp (-> facts-age days ago))})
      (sql/insert-record :catalogs {:id id :hash node :api_version 0 :catalog_version 0 :certname node :timestamp (to-timestamp (minus right-now (-> catalog-age days)))})))

  (let [version [:v4]]

    (testing (str "version " version)
      (testing "include total results count"
        (let [actual (:count (raw-retrieve-nodes version nil {:count? true}))]
          (is (= actual (count names)))))

      (testing "limit results"
        (doseq [[limit expected] [[1 1] [2 2] [100 5]]]
          (let [results (retrieve-node-names version nil {:limit limit})
                actual  (count results)]
            (is (= actual expected)))))

      (testing "order-by"
        (testing "rejects invalid fields"
          (is (thrown-with-msg?
               IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
               (retrieve-node-names version nil
                                    {:order-by [[:invalid-field :ascending]]}))))

        (testing "alphabetical fields"
          (doseq [[order expected] [[:ascending  ["node_a" "node_b" "node_c" "node_d" "node_e"]]
                                    [:descending ["node_e" "node_d" "node_c" "node_b" "node_a"]]]]
            (testing order
              (let [actual (retrieve-node-names version nil
                                                {:order-by [[:certname order]]})]
                (is (= actual expected))))))

        (testing "timestamp fields"
          (doseq [[order expected] [[:ascending  ["node_e" "node_b" "node_c" "node_d" "node_a"]]
                                    [:descending ["node_a" "node_d" "node_c" "node_b" "node_e"]]]]
            (testing order
              (let [actual (retrieve-node-names version nil
                                                {:order-by [[:facts_timestamp order]]})]
                (is (= actual expected))))))

        (testing "multiple fields"
          (doseq [[[timestamp-order name-order] expected] [[[:ascending :descending] ["node_d" "node_a" "node_e" "node_b" "node_c"]]
                                                           [[:descending :ascending] ["node_c" "node_b" "node_e" "node_a" "node_d"]]]]
            (testing (format "catalog-timestamp %s name %s" timestamp-order name-order)
              (let [actual (retrieve-node-names version nil
                                                {:order-by [[:catalog_timestamp timestamp-order]
                                                            [:certname name-order]]})]
                (is (= actual expected)))))))

      (testing "offset"
        (doseq [[order expected-sequences] [[:ascending  [[0 ["node_a" "node_b" "node_c" "node_d" "node_e"]]
                                                          [1 ["node_b" "node_c" "node_d" "node_e"]]
                                                          [2 ["node_c" "node_d" "node_e"]]
                                                          [3 ["node_d" "node_e"]]
                                                          [4 ["node_e"]]
                                                          [5 []]]]
                                            [:descending [[0 ["node_e" "node_d" "node_c" "node_b" "node_a"]]
                                                          [1 ["node_d" "node_c" "node_b" "node_a"]]
                                                          [2 ["node_c" "node_b" "node_a"]]
                                                          [3 ["node_b" "node_a"]]
                                                          [4 ["node_a"]]
                                                          [5 []]]]]]
          (testing order
            (doseq [[offset expected] expected-sequences]
              (let [actual (retrieve-node-names version nil
                                                {:order-by [[:certname order]]
                                                 :offset offset})]
                (is (= actual expected))))))))))
