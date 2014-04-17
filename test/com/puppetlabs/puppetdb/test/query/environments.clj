(ns com.puppetlabs.puppetdb.test.query.environments
  (:require [com.puppetlabs.puppetdb.query.environments :refer :all]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.scf.storage :as storage]
            [com.puppetlabs.puppetdb.fixtures :as fixt]))

(fixt/defixture super-fixture :each fixt/with-test-db)

(deftest test-all-environments
  (testing "without environments"
    (is (empty? (:result (all-environments)))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (is (= #{{:name "foo"}
             {:name "bar"}
             {:name "baz"}}
           (set (:result (all-environments)))))))

(deftest test-query-environment
  (testing "environment not present"
    (is (empty? (:result (query-environment "foo")))))
  (testing "environment present"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (is (= {:name "foo"}
           (:result (query-environment "foo"))))))
