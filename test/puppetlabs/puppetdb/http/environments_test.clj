(ns puppetlabs.puppetdb.http.environments-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]
            [puppetlabs.puppetdb.testutils.nodes :as tu-nodes]))

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def endpoints [[:v4 "/v4/environments"]])

;; RETRIEVAL

(defn get-response
  ([endpoint]
   (get-response endpoint nil))
  ([endpoint query]
   (let [resp (fixt/*app* (get-request endpoint query))]
     (if (string? (:body resp))
       resp
       (update-in resp [:body] slurp)))))

;; TESTS

(deftestseq test-all-environments
  [[version endpoint] endpoints]

  (testing "without environments"
    (is (empty? (json/parse-string (:body (get-response endpoint))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (fixt/without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (set (json/parse-string (:body (get-response endpoint))
                                      true))))))
    (fixt/without-db-var
     (fn []
       (let [res (get-response endpoint)]
         (is (= #{{:name "foo"}
                  {:name "bar"}
                  {:name "baz"}}
                (set @(future (json/parse-string (:body res)
                                                 true))))))))))

(deftestseq test-query-environment
  [[version endpoint] endpoints]

  (testing "without environments"
    (fixt/without-db-var
     (fn []
       (is (= 404 (:status (get-response (str endpoint "/foo"))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (fixt/without-db-var
     (fn []
       (is (= {:name "foo"}
              (json/parse-string (:body (get-response (str endpoint "/foo")))
                                 true)))))))

(deftestseq environment-subqueries
  [[version endpoint] endpoints]

  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query expected] (= expected
                             (-> (:body (get-response endpoint query))
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
  [[version endpoint] no-parent-endpoints]

  (let [{:keys [status body]} (get-response endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about environment foo"} (json/parse-string body true)))))
