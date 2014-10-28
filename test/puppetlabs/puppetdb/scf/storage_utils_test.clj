(ns puppetlabs.puppetdb.scf.storage-utils-test
  (:require [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage-utils :refer :all]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.fixtures :refer [with-test-db]]))

(use-fixtures :each with-test-db)

(deftest serialization
  (let [values ["foo" 0 "0" nil "nil" "null" [1 2 3] ["1" "2" "3"] {"a" 1 "b" [1 2 3]}]]
    (testing "serialized values should deserialize to the initial value"
      (doseq [value values]
        (is (= (json/parse-string (db-serialize value)) value))))
    (testing "serialized values should be unique"
      (doseq [value1 values
              value2 values]
        (let [str1 (db-serialize value1)
              str2 (db-serialize value2)]
          (when (= value1 value2)
            (is (= str1 str2)))
          (when-not (= value1 value2)
            (is (not= str1 str2)
                (str value1 " should not serialize the same as " value2)))))))
  (let [values ["foo" 0 {"z" 1 "a" 1}]
        expected ["foo" 0 {"a" 1 "z" 1}]]
    (testing "should sort beforehand"
      (is (= (json/parse-string (db-serialize values)) expected))))
  (let [sample {:b "asdf" :a {:z "asdf" :k [:z {:z 26 :a 1} :c] :a {:m "asdf" :b "asdf"}}}]
    (testing "serialized value should be sorted and predictable"
      (is (= (db-serialize sample)
             "{\"a\":{\"a\":{\"b\":\"asdf\",\"m\":\"asdf\"},\"k\":[\"z\",{\"a\":1,\"z\":26},\"c\"],\"z\":\"asdf\"},\"b\":\"asdf\"}")))))

(deftest ^{:hsqldb false} test-pg-extension?
  (testing "check if plpsql is installed"
    (is (true? (pg-extension? "plpgsql")))))

(deftest ^{:hsqldb false} test-index-exists?
  (testing "test to see if an index doesn't exists"
    (is (false? (index-exists? "somerandomname"))))
  (testing "test to see if an index does exist"
    (sql/do-commands "CREATE INDEX foobar ON fact_values(value_float)")
    (is (true? (index-exists? "foobar")))))

(deftest ^{:hsqldb false} test-postgres?-hsql
  (testing "false if hsqldb"
    (= (false? (postgres?)))))

(deftest ^{:postgres false} test-postgres?-pg
  (testing "true if postgresql"
    (= (true? (postgres?)))))
