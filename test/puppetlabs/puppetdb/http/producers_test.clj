(ns puppetlabs.puppetdb.http.producers-test
  (:require [puppetlabs.puppetdb.cheshire :as json]
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

(def endpoints [[:v4 "/v4/producers"]])

(deftest-http-app test-all-producers
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without producers"
    (is (empty? (query-result method endpoint))))

  (testing "with producers"
    (doseq [prod ["foo" "bar" "baz"]]
      (storage/ensure-producer prod))

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

(deftest-http-app test-query-producer
  [[version endpoint] endpoints
   method [:get :post]]

  (testing "without producers"
    (without-db-var
     (fn []
       (is (= 404 (:status (query-response method (str endpoint "/foo"))))))))

  (testing "with producers"
    (doseq [prod ["foo" "bar" "baz"]]
      (storage/ensure-producer prod))
    (without-db-var
     (fn []
       (is (= {:name "foo"}
              (-> (query-response method (str endpoint "/foo"))
                  :body
                  (json/parse-string true))))))))

(deftest-http-app producer-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (tu-nodes/store-example-nodes)]
    (are [query expected] (= expected
                             (query-result method endpoint query))

         ["=" "name" "foo.com"]
         #{{:name "foo.com"}}

         ["~" "name" "f.*"]
         #{{:name "foo.com"}}

         ["not" ["=" "name" "foo.com"]]
         #{{:name "bar.com"}
           {:name "mom.com"}}

         ;;;;;;;;;;;;
         ;; Basic reports subquery examples
         ;;;;;;;;;;;;

         ;; In syntax: select_reports
         ["in" "name"
          ["extract" "producer"
           ["select_reports"
            ["and"
             ["=" "certname" "web1.example.com"]
             ["=" "environment" "DEV"]]]]]
         #{{:name "bar.com"}}

         ;; In syntax: from
         ["in" "name"
          ["from" "reports"
           ["extract" "producer"
            ["and"
             ["=" "certname" "web1.example.com"]
             ["=" "environment" "DEV"]]]]]
         #{{:name "bar.com"}}

         ;; Implicit subquery syntax
         ["subquery" "reports"
          ["and"
           ["=" "certname" "web1.example.com"]
           ["=" "environment" "DEV"]]]
         #{{:name "bar.com"}}

         ;;;;;;;;;;;;;
         ;; Not-wrapped subquery syntax
         ;;;;;;;;;;;;;

         ;; In syntax: select_reports
         ["not"
          ["in" "name"
           ["extract" "producer"
            ["select_factsets"
             ["and"
              ["=" "certname" "web1.example.com"]
              ["=" "environment" "DEV"]]]]]]
         #{{:name "bar.com"}
           {:name "mom.com"}}

         ;; In syntax: from
         ["not"
          ["in" "name"
           ["from" "factsets"
            ["extract" "producer"
             ["and"
              ["=" "certname" "web1.example.com"]
              ["=" "environment" "DEV"]]]]]]
         #{{:name "bar.com"}
           {:name "mom.com"}}

         ;; Implict subquery syntax
         ["not"
          ["subquery" "factsets"
          ["and"
           ["=" "certname" "web1.example.com"]
           ["=" "environment" "DEV"]]]]
         #{{:name "bar.com"}
           {:name "mom.com"}}))

  (testing "failed comparison"
    (are [query]
          (let [{:keys [status body]} (query-response method endpoint query)]
            (re-find
             #"Query operators >,>=,<,<= are not allowed on field name" body))

      ["<=" "name" "foo.com"]
      [">=" "name" "foo.com"]
      ["<" "name" "foo.com"]
      [">" "name" "foo.com"])))

(def no-parent-endpoints [[:v4 "/v4/producers/foo.com/catalogs"]
                          [:v4 "/v4/producers/foo.com/factsets"]
                          [:v4 "/v4/producers/foo.com/reports"]])

(deftest-http-app unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (testing "producer-exists? function"
    (doseq [prod ["bar.com" "baz.com" "mom.com"]]
      (storage/ensure-producer prod))
    (is (= false (eng/object-exists? :producer "foo.com")))
    (is (= true (eng/object-exists? :producer "bar.com")))
    (is (= true (eng/object-exists? :producer "baz.com")))
    (is (= true (eng/object-exists? :producer "mom.com"))))

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= status HttpURLConnection/HTTP_NOT_FOUND))
    (is (= {:error "No information is known about producer foo.com"}
           (json/parse-string body true)))))
