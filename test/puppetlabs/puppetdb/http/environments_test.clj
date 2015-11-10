(ns puppetlabs.puppetdb.http.environments-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [puppetlabs.puppetdb.query-eng :as eng]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.db :refer [without-db-var]]
            [puppetlabs.puppetdb.testutils.http :refer [deftest-http-app
                                                        query-response
                                                        query-result]]
            [puppetlabs.puppetdb.testutils.nodes :as tu-nodes]))

(def endpoints [[:v4 "/v4/environments"]])

(deftest-http-app test-all-environments
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (is (empty? (query-result method endpoint))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (query-result method endpoint)))))

    (without-db-var
     (fn []
       (let [res (query-response method endpoint)]
         (is (= #{{:name "foo"}
                  {:name "bar"}
                  {:name "baz"}}
                (set @(future (-> (query-response method endpoint)
                                  :body
                                  slurp
                                  (json/parse-string true)))))))))))

(deftest-http-app test-query-environment
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (without-db-var
     (fn []
       (is (= 404 (:status (query-response method (str endpoint "/foo"))))))))

  (testing "with environments"
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))
    (without-db-var
     (fn []
       (is (= {:name "foo"}
              (-> (query-response method (str endpoint "/foo"))
                  :body
                  (json/parse-string true))))))))

(deftest-http-app environment-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env))

    (are [query expected] (= expected
                             (query-result method endpoint query))

         ["=" "name" "foo"]
         #{{:name "foo"}}

         ["~" "name" "f.*"]
         #{{:name "foo"}}

         ["not" ["=" "name" "foo"]]
         #{{:name "DEV"}
           {:name "bar"}
           {:name "baz"}}

         ;;;;;;;;;;;;
         ;; Basic facts subquery examples
         ;;;;;;;;;;;;

         ;; In syntax
         ["in" "name"
          ["extract" "environment"
           ["select_facts"
            ["and"
             ["=" "name" "operatingsystem"]
             ["=" "value" "Debian"]]]]]
         #{{:name "DEV"}}

         ;; Implicit subquery syntax
         ["subquery" "facts"
          ["and"
           ["=" "name" "operatingsystem"]
           ["=" "value" "Debian"]]]
         #{{:name "DEV"}}

         ;;;;;;;;;;;;;
         ;; Not-wrapped subquery syntax
         ;;;;;;;;;;;;;

         ;; In syntax
         ["not"
          ["in" "name"
           ["extract" "environment"
            ["select_facts"
             ["and"
              ["=" "name" "operatingsystem"]
              ["=" "value" "Debian"]]]]]]
         #{{:name "foo"}
           {:name "bar"}
           {:name "baz"}}

         ;; Implict subquery syntax
         ["not"
          ["subquery" "facts"
          ["and"
           ["=" "name" "operatingsystem"]
           ["=" "value" "Debian"]]]]
         #{{:name "foo"}
          {:name "bar"}
          {:name "baz"}}

         ;;;;;;;;
         ;; Complex subquery example
         ;;;;;;;;

         ;; In syntax
         ["in" "name"
          ["extract" "environment"
           ["select_facts"
            ["and"
             ["=" "name" "hostname"]
             ["in" "value"
              ["extract" "title"
               ["select_resources"
                ["=" "type" "Class"]]]]]]]]
         #{{:name "DEV"}}

         ;; Note: fact/resource comparison isn't a natural
         ;; join, so there is no implicit syntax here.
         ))

  (testing "failed comparison"
    (are [query]
          (let [{:keys [status body]} (query-response method endpoint query)]
            (re-find
             #"Query operators >,>=,<,<= are not allowed on field name" body))

      ["<=" "name" "foo"]
      [">=" "name" "foo"]
      ["<" "name" "foo"]
      [">" "name" "foo"])))

(def no-parent-endpoints [[:v4 "/v4/environments/foo/events"]
                          [:v4 "/v4/environments/foo/facts"]
                          [:v4 "/v4/environments/foo/reports"]
                          [:v4 "/v4/environments/foo/resources"]])

(deftest-http-app unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (testing "environment-exists? function"
    (doseq [env ["bobby" "dave" "charlie"]]
      (storage/ensure-environment env))
    (is (= true (eng/object-exists? :environment "bobby")))
    (is (= true (eng/object-exists? :environment "dave")))
    (is (= true (eng/object-exists? :environment "charlie")))
    (is (= false (eng/object-exists? :environment "ussr"))))

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about environment foo"}
           (json/parse-string body true)))))
