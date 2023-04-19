(ns puppetlabs.puppetdb.random-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.random
    :refer [distribute
            random-bool
            random-node-name
            random-pp-path
            random-pronouncable-word
            random-sha1
            random-string
            random-string-alpha
            random-type-name
            safe-sample-normal]]))

(deftest test-random-string
  (testing "should return a string of specified length"
    (is (= 8 (count (random-string 8))))
    (is (= 30 (count (random-string 30))))
    (is (= 100 (count (random-string 100)))))

  (testing "should only accept a positive integer"
    (is (thrown? IllegalArgumentException (random-string -1)))
    (is (thrown? ClassCastException (random-string "asdf")))))

(deftest test-random-string-alpha
  (testing "should return a string of specified length"
    (is (= 8 (count (random-string-alpha 8))))
    (is (= 30 (count (random-string-alpha 30))))
    (is (= 100 (count (random-string-alpha 100)))))

  (testing "should only accept a positive integer"
    (is (thrown? IllegalArgumentException (random-string-alpha -1)))
    (is (thrown? ClassCastException (random-string-alpha "asdf")))))

(deftest test-random-bool
  (testing "should return a boolean"
    (is (boolean? (random-bool)))))

(deftest test-random-node-name
  (testing "should return a random node name"
    (is (string? (random-node-name)))
    (is (= 30 (count (random-node-name))))))

(deftest test-random-type-name
  (testing "should return a random type name"
    (is (string? (random-type-name)))
    (is (= 10 (count (random-type-name))))))

(deftest test-random-pp-path
  (testing "should return a random path"
    (is (string? (random-pp-path)))
    (is (= 54 (count (random-pp-path))))))

(deftest test-random-sha1
  (testing "returns a random sha1 hash"
    (is (string? (random-sha1)))
    (is (re-matches #"[\da-z]{40}" (random-sha1)))
    (is (re-matches #"[\da-z]{40}" (random-sha1 1000)))))

(deftest test-random-pronouncable-word
  (let [consonants "[bcdfghjklmnpqrstvwxz]"
        exp-regex-str (format "(?:%s[aeiouy])" consonants)]
    (testing "returns a random pronouncable word string"
      (is (re-matches (re-pattern (str exp-regex-str "{3}")) (random-pronouncable-word)))
      (is (re-matches (re-pattern (str exp-regex-str "{4}")) (random-pronouncable-word 8)))
      (is (re-matches (re-pattern (str exp-regex-str "{3}" consonants)) (random-pronouncable-word 7))))))

(deftest test-distribute
  (let [v5 (vec (map {} (range 5)))
        v20 (vec (map {} (range 20)))
        v100 (vec (map {} (range 100)))
        poke (fn [e]
               (let [{:keys [counter] :or {counter 0}} e]
                 (assoc e :counter (+ 1 counter))))
        tally (fn [v]
                (let [tcount (fn [a {:keys [counter] :or {counter 0}}] (+ a counter))]
                  (reduce tcount 0 v)))
        options {:debug false}]
    (testing "zero"
      (let [dv5 (distribute v5 poke 0 options)
            dv20 (distribute v20 poke 0 options)
            dv100 (distribute v100 poke 0 options)]
        (is (= dv5 v5))
        (is (= dv20 v20))
        (is (= dv100 v100))))
    (testing "poke fractionally per"
      (let [dv5 (distribute v5 poke 0.5 options)
            dv20 (distribute v20 poke 0.5 options)
            dv100 (distribute v100 poke 0.5 options)]
        (is (<= 1 (tally dv5) 3))
        (is (<= 7 (tally dv20) 13))
        (is (<= 35 (tally dv100) 65))))
    (testing "poke avg once per"
      (let [dv5 (distribute v5 poke 1 options)
            dv20 (distribute v20 poke 1 options)
            dv100 (distribute v100 poke 1 options)]
        (is (<= 4 (tally dv5) 6))
        (is (<= 14 (tally dv20) 26))
        (is (<= 70 (tally dv100) 130))))
    (testing "poke avg twice per"
      (let [dv5 (distribute v5 poke 2 options)
            dv20 (distribute v20 poke 2 options)
            dv100 (distribute v100 poke 2 options)]
        (is (<= 7 (tally dv5) 13))
        (is (<= 28 (tally dv20) 52 ))
        (is (<= 140 (tally dv100) 260))))
    (testing "poke avg ten times per"
      (let [dv5 (distribute v5 poke 10 options)
            dv20 (distribute v20 poke 10 options)
            dv100 (distribute v100 poke 10 options)]
        (is (<= 35 (tally dv5) 65))
        (is (<= 140 (tally dv20) 260))
        (is (<= 700 (tally dv100) 1300))))
    (testing "overrides"
        (let [dv20 (distribute v20 poke 1
                               (merge options
                                      {:standard-deviation 10 :lowerb 5 :upperb 50}))]
          (is (<= 5 (tally dv20 ) 50))))))

(deftest safe-sample-normal-test
  (testing "positive"
    (is (<= 0 (safe-sample-normal 2 1) 4)))
  (testing "bounds"
    (is (thrown-with-msg? ArithmeticException #"Called safe-sample-normal with lowerb of 3 which is greater than mean of 2." (safe-sample-normal 2 1 {:lowerb 3})))
    (is (thrown-with-msg? ArithmeticException #"Called safe-sample-normal with upperb of 1 which is less than mean of 2." (safe-sample-normal 2 1 {:upperb 1}))))
  (testing "negative with default bounds"
    (is (thrown-with-msg? ArithmeticException #"Called safe-sample-normal with lowerb of 0 which is greater than mean of -2." (safe-sample-normal -2 1)))
    (is (thrown-with-msg? ArithmeticException #"Called safe-sample-normal with upperb of -4 which is less than mean of -2." (safe-sample-normal -2 1 {:lowerb -5}))))
  (testing "negative with appropriate bounds"
    (is (<= -3 (safe-sample-normal -2 1 {:lowerb -3 :upperb 0}) 0))))
