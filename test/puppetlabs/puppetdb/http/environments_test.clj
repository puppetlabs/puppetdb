(ns puppetlabs.puppetdb.http.environments-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.jdbc :refer [with-db-transaction]]
            [puppetlabs.puppetdb.scf.storage :as storage]
            [puppetlabs.puppetdb.query-eng :as eng]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.db :refer [without-db-var]]
            [puppetlabs.puppetdb.testutils.http :refer [deftest-http-app
                                                        query-response
                                                        query-result]]
            [puppetlabs.puppetdb.testutils.nodes :as tu-nodes])
  (:import
   (java.net HttpURLConnection)))

(def endpoints [[:v4 "/v4/environments"]])

(deftest-http-app test-all-environments
  [[_version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (is (empty? (query-result method endpoint))))

  (testing "with environments"
    (with-db-transaction []
      (doseq [env ["foo" "bar" "baz"]]
        (storage/ensure-environment env)))

    (without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (query-result method endpoint)))))

    (without-db-var
     (fn []
       (is (= #{{:name "foo"}
                {:name "bar"}
                {:name "baz"}}
              (set @(future (-> (query-response method endpoint)
                                :body
                                slurp
                                (json/parse-string true))))))))))

(deftest-http-app test-query-environment
  [[_version endpoint] endpoints
   method [:get :post]]

  (testing "without environments"
    (without-db-var
     (fn []
       (is (= 404 (:status (query-response method (str endpoint "/foo"))))))))

  (testing "with environments"
    (with-db-transaction []
      (doseq [env ["foo" "bar" "baz"]]
        (storage/ensure-environment env)))
    (without-db-var
     (fn []
       (is (= {:name "foo"}
              (-> (query-response method (str endpoint "/foo"))
                  :body
                  (json/parse-string true))))))))

(deftest-http-app environment-queries
  [[_version endpoint] endpoints
   method [:get :post]]

  (tu-nodes/store-example-nodes)
  (with-db-transaction []
    (doseq [env ["foo" "bar" "baz"]]
      (storage/ensure-environment env)))

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

    ;; In syntax: select_facts
    ["in" "name"
     ["extract" "environment"
      ["select_facts"
       ["and"
        ["=" "name" "operatingsystem"]
        ["=" "value" "Debian"]]]]]
    #{{:name "DEV"}}

    ;; In syntax: from
    ["in" "name"
     ["from" "facts"
      ["extract" "environment"
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

    ;; In syntax: select_facts
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

    ;; In syntax: from
    ["not"
     ["in" "name"
      ["from" "facts"
       ["extract" "environment"
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

    ;; In syntax: select_<entity>
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

    ;; In syntax: from
    ["in" "name"
     ["from" "facts"
      ["extract" "environment"
       ["and"
        ["=" "name" "hostname"]
        ["in" "value"
         ["from" "resources"
          ["extract" "title"
           ["=" "type" "Class"]]]]]]]]
    #{{:name "DEV"}}


    ;; Note: fact/resource comparison isn't a natural
    ;; join, so there is no implicit syntax here.
    )

  (testing "failed comparison"
    (are [query]
          (let [{:keys [_status body]} (query-response method endpoint query)]
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
  [[_version endpoint] no-parent-endpoints
   method [:get :post]]

  (testing "environment-exists? function"
    (with-db-transaction []
      (doseq [env ["bobby" "dave" "charlie"]]
        (storage/ensure-environment env)))
    (is (= true (eng/object-exists? :environment "bobby")))
    (is (= true (eng/object-exists? :environment "dave")))
    (is (= true (eng/object-exists? :environment "charlie")))
    (is (= false (eng/object-exists? :environment "ussr"))))

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= HttpURLConnection/HTTP_NOT_FOUND status))
    (is (= {:error "No information is known about environment foo"}
           (json/parse-string body true)))))
