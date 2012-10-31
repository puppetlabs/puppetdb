(ns com.puppetlabs.test.validation
  (:use clojure.test
        com.puppetlabs.validation
        com.puppetlabs.puppetdb.examples.report))

(deftest defining-models
  (defmodel TestModel
    {:a {:optional? true
         :type :string}
     :b :integer
     :c :number})

  (testing "defs the given name with the appropriate structure"
    (is (= TestModel {:name "TestModel"
                      :fields {:a {:optional? true
                                   :type :string}
                               :b {:optional? false
                                   :type :integer}
                               :c {:optional? false
                                   :type :number}}})))

  (testing "returns the object if everything is okay"
    (let [test-obj {:a "foo" :b 5 :c 12.0}]
      (is (= (validate-against-model TestModel test-obj) test-obj))))

  (testing "raises errors for missing keys"
    (is (thrown-with-msg? IllegalArgumentException #"TestModel is missing keys: :c$"
                          (validate-against-model TestModel {:a "foo" :b 5})))

    (is (thrown-with-msg? IllegalArgumentException #"TestModel is missing keys: :b, :c$"
                          (validate-against-model TestModel {:a "foo"}))))

  (testing "does not raise an error for a missing optional key"
    (is (= (validate-against-model TestModel {:b 5 :c 12.0}) {:b 5 :c 12.0})))

  (testing "does not raise an error for a nil optional key"
    (is (= (validate-against-model TestModel {:a nil :b 5 :c 12.0}) {:a nil :b 5 :c 12.0})))

  (testing "raises an error for a nil non-optional key"
    (is (thrown? IllegalArgumentException
                 (validate-against-model TestModel {:a "foo" :b nil :c 12.0}))))

  (testing "raises errors for extra keys"
    (is (thrown-with-msg? IllegalArgumentException #"TestModel has unknown keys: :d$"
                          (validate-against-model TestModel {:a "foo" :b 5 :c 12.0 :d "nooooooooooooo"})))

    (is (thrown-with-msg? IllegalArgumentException #"TestModel has unknown keys: :d, :e$"
                          (validate-against-model TestModel {:a "foo" :b 5 :c 12.0 :d "nooooooooooooo" :e "waaaaaaaaaaaay"}))))

  (testing "raises errors for invalid types"
    (is (thrown-with-msg? IllegalArgumentException #"TestModel key :a should be String, got 3$"
                          (validate-against-model TestModel {:a 3 :b 5 :c 12.0})))))
