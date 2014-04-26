(ns com.puppetlabs.puppetdb.test.http.environments
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.scf.storage :as storage]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.testutils :refer [get-request]]
            [com.puppetlabs.puppetdb.testutils.nodes :as tu-nodes]))

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

    (fixt/without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (set (json/parse-string (:body (fixt/*app* (get-request "/v4/environments")))
                                      true))))))
    (fixt/without-db-var
     (fn []
       (let [res (fixt/*app* (get-request "/v4/environments"))]
         (is (= #{{:name "foo"}
                  {:name "bar"}
                  {:name "baz"}}
                (set @(future (json/parse-string (:body res)
                                                 true))))))))))

(deftest test-query-environment
  (testing "without environments"
    (fixt/without-db-var
     (fn []
       (is (empty? (json/parse-string (:body (fixt/*app* (get-request "/v4/environments/foo")))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (fixt/without-db-var
     (fn []
       (is (= {:name "foo"}
              (json/parse-string (:body (fixt/*app* (get-request "/v4/environments/foo")))
                                 true)))))))

(deftest environment-subqueries
  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query expected] (= expected (json/parse-string (:body (fixt/*app* (get-request "/v4/environments" query))) true))

         ["in" "name"
          ["extract" "environment"
           ["select-facts"
            ["and"
             ["=" "name" "operatingsystem"]
             ["=" "value" "Debian"]]]]]
         [{:name "DEV"}]

         ["not"
          ["in" "name"
           ["extract" "environment"
            ["select-facts"
             ["and"
              ["=" "name" "operatingsystem"]
              ["=" "value" "Debian"]]]]]]
         [{:name "foo"}
          {:name "bar"}
          {:name "baz"}]

         ["in" "name"
          ["extract" "environment"
           ["select-facts"
            ["and"
             ["=" "name" "hostname"]
             ["in" "value"
              ["extract" "title"
               ["select-resources"
                ["and"
                 ["=" "type" "Class"]]]]]]]]]
         [{:name "DEV"}])

        (are [env query expected] (= expected (json/parse-string (:body (fixt/*app* (get-request (str "/v4/environments/" env) query))) true))

         "DEV"
         ["in" "name"
          ["extract" "environment"
           ["select-facts"
            ["and"
             ["=" "name" "operatingsystem"]
             ["=" "value" "Debian"]]]]]
         {:name "DEV"}

         "foo"
         ["in" "name"
          ["extract" "environment"
           ["select-facts"
            ["and"
             ["=" "name" "operatingsystem"]
             ["=" "value" "Debian"]]]]]
         nil)))
