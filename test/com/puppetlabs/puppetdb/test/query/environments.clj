(ns com.puppetlabs.puppetdb.test.query.environments
  (:require [com.puppetlabs.puppetdb.query.environments :refer :all]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.scf.storage :as storage]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.cheshire :as json]))

(fixt/defixture super-fixture :each fixt/with-test-db)

(deftest test-all-environments
  (testing "without environments"
    (is (empty? (:result (query-environments :v4 (query->sql :v4 nil))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (is (= #{{:name "foo"}
             {:name "bar"}
             {:name "baz"}}
           (set (:result (query-environments :v4 (query->sql :v4 nil))))))))

(def jsonify (comp json/parse-strict-string json/generate-string))

(deftest test-environment-queries
  (testing "without environments"
    (is (empty? (:result (query-environments :v4 (query->sql :v4 nil))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query result] (= result (set (:result (query-environments :v4 (query->sql :v4 (jsonify query))))))

      '[= name foo]
      #{{:name "foo"}}

      '["~" name f.*]
      #{{:name "foo"}}

      '[not [= name foo]]
      #{{:name "bar"}
        {:name "baz"}}

      '[not ["~" name f.*]]
      #{{:name "bar"}
        {:name "baz"}}

      '[or
        [= name foo]
        [= name bar]]
      #{{:name "foo"}
        {:name "bar"}}

      '[and
        ["~" name f.*]
        ["~" name .*o]]
      #{{:name "foo"}}

      '[and
        ["~" name f.*]
        ["~" name .*o]]
      #{{:name "foo"}})))

(deftest test-failed-comparison
  (are [query] (thrown-with-msg? IllegalArgumentException
                                 #"Query operators >,>=,<,<= are not allowed on field name"
                                 (query-environments :v4 (query->sql :v4 (jsonify query))))

    '[<= name foo]
    '[>= name foo]
    '[< name foo]
    '[> name foo]))
