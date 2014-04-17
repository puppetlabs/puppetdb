(ns com.puppetlabs.puppetdb.test.http.environments
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.scf.storage :as storage]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.testutils :refer [get-request]]))

(fixt/defixture super-fixture :each fixt/with-test-db fixt/with-http-app)

(deftest test-old-version-failures
  (is (thrown-with-msg? IllegalArgumentException
                        #"Environment queries not supported on v3"
                        (fixt/*app* (get-request "/v3/environments"))))
  (is (thrown-with-msg? IllegalArgumentException
                        #"Environment queries not supported on v2"
                        (fixt/*app* (get-request "/v2/environments")))))

(deftest test-all-environments
  (testing "without environments"
    (is (empty? (json/parse-string (:body (fixt/*app* (get-request "/v4/environments")))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (is (= #{{:name "foo"}
             {:name "bar"}
             {:name "baz"}}
           (set (json/parse-string (:body (fixt/*app* (get-request "/v4/environments")))
                                   true))))))

(deftest test-query-environment
  (testing "without environments"
    (is (empty? (json/parse-string (:body (fixt/*app* (get-request "/v4/environments/foo")))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (is (= {:name "foo"}
           (json/parse-string (:body (fixt/*app* (get-request "/v4/environments/foo")))
                              true)))))
