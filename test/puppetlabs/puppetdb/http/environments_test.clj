(ns puppetlabs.puppetdb.http.environments-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response
                                                        query-result
                                                        order-param]]
            [puppetlabs.puppetdb.testutils.nodes :as tu-nodes]))

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def endpoints [[:v4 "/v4/environments"]])

;; TESTS

(deftestseq test-all-environments
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (is (empty? (query-result method endpoint))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (fixt/without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (query-result method endpoint)))))

    (fixt/without-db-var
     (fn []
       (let [res (query-response method endpoint)]
         (is (= #{{:name "foo"}
                  {:name "bar"}
                  {:name "baz"}}
                (set @(future (-> (query-response method endpoint)
                                  :body
                                  slurp
                                  (json/parse-string true)))))))))))

(deftestseq test-query-environment
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (fixt/without-db-var
     (fn []
       (is (= 404 (:status (query-response method (str endpoint "/foo"))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (fixt/without-db-var
     (fn []
       (is (= {:name "foo"}
              (-> (query-response method (str endpoint "/foo"))
                  :body
                  (json/parse-string true))))))))

(deftestseq environment-subqueries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query expected] (= expected
                             (-> (query-response method endpoint query)
                                 :body
                                 slurp
                                 (json/parse-string true)))

         ["in" "name"
          ["extract" "environment"
           ["select_facts"
            ["and"
             ["=" "name" "operatingsystem"]
             ["=" "value" "Debian"]]]]]
         [{:name "DEV"}]

         ["not"
          ["in" "name"
           ["extract" "environment"
            ["select_facts"
             ["and"
              ["=" "name" "operatingsystem"]
              ["=" "value" "Debian"]]]]]]
         [{:name "foo"}
          {:name "bar"}
          {:name "baz"}]

         ["in" "name"
          ["extract" "environment"
           ["select_facts"
            ["and"
             ["=" "name" "hostname"]
             ["in" "value"
              ["extract" "title"
               ["select_resources"
                ["and"
                 ["=" "type" "Class"]]]]]]]]]
         [{:name "DEV"}])))

(def no-parent-endpoints [[:v4 "/v4/environments/foo/events"]
                          [:v4 "/v4/environments/foo/facts"]
                          [:v4 "/v4/environments/foo/reports"]
                          [:v4 "/v4/environments/foo/resources"]])

(deftestseq unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about environment foo"} (json/parse-string body true)))))
