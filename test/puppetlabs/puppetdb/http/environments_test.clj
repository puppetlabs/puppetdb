(ns puppetlabs.puppetdb.http.environments-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]
            [puppetlabs.puppetdb.testutils.nodes :as tu-nodes]))

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def endpoints [[:v4 "/v4/environments"]])

(deftestseq test-all-environments
  [[version endpoint] endpoints]

  (testing "without environments"
    (is (empty? (json/parse-string (slurp (:body (fixt/*app* (get-request endpoint))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (fixt/without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (set (json/parse-string (slurp (:body (fixt/*app* (get-request endpoint))))
                                      true))))))
    (fixt/without-db-var
     (fn []
       (let [res (fixt/*app* (get-request endpoint))]
         (is (= #{{:name "foo"}
                  {:name "bar"}
                  {:name "baz"}}
                (set @(future (json/parse-string (slurp (:body res))
                                                 true))))))))))

(deftestseq test-query-environment
  [[version endpoint] endpoints]

  (testing "without environments"
    (fixt/without-db-var
     (fn []
       (is (= 404 (:status (fixt/*app* (get-request (str endpoint "/foo")))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (fixt/without-db-var
     (fn []
       (is (= {:name "foo"}
              (json/parse-string (:body (fixt/*app* (get-request (str endpoint "/foo"))))
                                 true)))))))

(deftestseq environment-subqueries
  [[version endpoint] endpoints]

  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query expected] (= expected (json/parse-string (slurp (:body (fixt/*app* (get-request endpoint query))))
                                                         true))

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
         [{:name "DEV"}])))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/environments" (omap/ordered-map
                  ["~" "name" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "name" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftestseq ^{:hsqldb false} pg-invalid-regexps
  [[version endpoint] facts-endpoints]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (get-response endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))
