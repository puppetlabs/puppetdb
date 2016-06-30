(ns puppetlabs.puppetdb.http.resources-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request paged-results]]
            [puppetlabs.puppetdb.testutils.resources :refer [store-example-resources]]
            [puppetlabs.puppetdb.testutils.http
             :refer [*app*
                     deftest-http-app
                     query-response
                     ordered-query-result
                     vector-param]]
            [flatland.ordered.map :as omap]))

(def v4-endpoint "/v4/resources")
(def v4-environments-endpoint "/v4/environments/DEV/resources")

(def endpoints [[:v4 v4-endpoint]])

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= http/status-ok (:status response)))
  (is (= http/json-response-content-type (tu/content-type response)))
  (is (= body (if (:body response)
                (set (json/parse-string (slurp (:body response)) true))
                nil))))

(defn query-result
  [response]
  (-> response
      :body
      slurp
      (json/parse-string true)
      set))

(deftest-http-app resource-endpoint-tests
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [foo1 bar1 foo2 bar2] :as expected} (store-example-resources)]
    (testing "query without filter should not fail"
      (let [response (query-response method endpoint)
            body     (json/parse-string (slurp (get response :body "null")) true)]
        (is (= 200 (:status response)))))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["~" "tag" "tw"] #{foo1 bar1}]

                              [["and"
                                ["=" "certname" "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["and"
                                ["~" "certname" "one.lo.*"]
                                ["=" "type" "File"]]
                               #{foo1}]

                              [["=" ["parameter" "ensure"] "file"] #{foo1 bar1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1 bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is-response-equal (query-response method endpoint query) result)))

    (testing "only v4 or after queries"
      (doseq [[query result] [[["~" ["parameter" "owner"] "ro.t"] #{foo1 bar1}]
                              [["not" ["~" ["parameter" "owner"] "ro.t"]] #{foo2 bar2}]]]
        (is (= (query-result (query-response method endpoint query)) result))))

    (testing "dot-style querying for parameters"
      (doseq [[query result] [[["=" "parameters.ensure" "file"] #{foo1 bar1}]
                              [["=" "parameters.owner" "root"] #{foo1 bar1}]
                              [["=" "parameters.acl" ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is (= (query-result (query-response method endpoint query)) result))))

    (testing "fact subqueries are supported"
      (let [{:keys [body status]}
            (query-response method endpoint
                            ["and"
                             ["=" "type" "File"]
                             ["in" "certname"
                              ["extract" "certname"
                               ["select_facts"
                                ["and"
                                 ["=" "name" "operatingsystem"]
                                 ["=" "value" "Debian"]]]]]])]
        (is (= status http/status-ok))
        (is (= (set (json/parse-string (slurp body) true)) #{foo1})))

      ;; Using the value of a fact as the title of a resource
      (let [{:keys [body status]} (query-response method endpoint
                                                ["in" "title" ["extract" "value" ["select_facts"
                                                                                  ["=" "name" "message"]]]])]
        (is (= status http/status-ok))
        (is (= (set (json/parse-string (slurp body) true)) #{foo2 bar2}))))

    (testing "resource subqueries are supported"
      ;; Fetch exported resources and their corresponding collected versions
      (let [{:keys [body status]} (query-response method endpoint
                                                ["or"
                                                 ["=" "exported" true]
                                                 ["and"
                                                  ["=" "exported" false]
                                                  ["in" "title" ["extract" "title" ["select_resources"
                                                                                    ["=" "exported" true]]]]]])]
        (is (= status http/status-ok))
        (is (= (set (json/parse-string (slurp body) true)) #{foo2 bar2}))))

    (testing "error handling"
      (let [response (query-response method endpoint ["="])
            body     (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (= (:headers response) {"Content-Type" http/error-response-content-type}))
        (is (re-find #"= requires exactly two arguments" body))))

    (testing "query with filter should exclude deactivated nodes"
      ;; After deactivating one.local, it's resources should not appear
      ;; in the results
      (scf-store/deactivate-node! "one.local")

      (doseq [[query result] [[["=" "type" "File"] #{bar1}]
                              [["=" "tag" "one"] #{bar1}]
                              [["=" "tag" "two"] #{bar1}]
                              [["and"
                                ["=" "certname" "one.local"]
                                ["=" "type" "File"]]
                               #{}]
                              [["=" ["parameter" "ensure"] "file"] #{bar1}]
                              [["=" ["parameter" "owner"] "root"] #{bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{bar1}]]]
        (is-response-equal (query-response method endpoint query) result)))))

(deftest-http-app environments-resource-endpoint
  [[version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 bar1 foo2 bar2] :as results} (store-example-resources)
        dev-endpoint (str "/" (name version) "/environments/DEV/resources")
        prod-endpoint (str "/" (name version) "/environments/PROD/resources")]

    (doseq [endpoint [dev-endpoint prod-endpoint]]
      (testing (str "query without filter should not fail for endpoint " endpoint)
        (let [response (query-response method endpoint)
              body     (get response :body "null")]
          (is (= 200 (:status response))))))

    (testing "DEV query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1}]
                              [["=" "tag" "one"] #{foo1}]
                              [["=" "tag" "two"] #{foo1}]
                              [["and"
                                ["=" "certname" "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["=" ["parameter" "ensure"] "file"] #{foo1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1}]]]
        (is-response-equal (query-response method dev-endpoint query) result)))

    (testing "PROD query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{bar1}]
                              [["=" "tag" "one"] #{bar1}]
                              [["=" "tag" "two"] #{bar1}]
                              [["and"
                                ["=" "certname" "one.local"]
                                ["=" "type" "File"]]
                               #{}]
                              [["=" ["parameter" "ensure"] "file"] #{bar1}]
                              [["=" ["parameter" "owner"] "root"] #{bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{bar1}]]]
        (is-response-equal (query-response method prod-endpoint query) result)))))

(deftest-http-app query-sourcefile-sourceline
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [bar2] :as results} (store-example-resources)]

    (testing "sourcefile and source is not supported"
      (let [query ["=" "sourceline" 22]
            response (query-response method endpoint query)]
        (is (= http/status-bad-request (:status response)))
        (is (= (:headers response) {"Content-Type" http/error-response-content-type}))
        (is (re-find #"'sourceline' is not a queryable object for resources, known queryable objects are" (:body response))))
      (let [query ["~" "sourcefile" "foo"]
            response (query-response method endpoint query)]
        (is (= http/status-bad-request (:status response)))
        (is (= (:headers response) {"Content-Type" http/error-response-content-type}))
        (is (re-find #"'sourcefile' is not a queryable object for resources, known queryable objects are" (:body response))))
      (let [query ["=" "sourcefile" "/foo/bar"]
            response (query-response method endpoint query)]
        (is (= http/status-bad-request (:status response)))
        (is (= (:headers response) {"Content-Type" http/error-response-content-type}))
        (is (re-find #"'sourcefile' is not a queryable object for resources, known queryable objects are" (:body response)))))

    (testing "query by file and line is supported"
      (let [query ["=" "file" "/foo/bar"]
            result #{bar2}]
        (is-response-equal (query-response method endpoint query) result))
      (let [query ["~" "file" "foo"]
            result #{bar2}]
        (is-response-equal (query-response method endpoint query) result))
      (let [query ["=" "line" 22]
            result #{bar2}]
        (is-response-equal (query-response method endpoint query) result))

      (let [query ["and"
                   [">" "line" 21]
                   ["<" "line" 23]]
            result #{bar2}]
        (is-response-equal (query-response method endpoint query) result)))))

(deftest-http-app resource-query-paging
  [[version endpoint] endpoints
   method [:get :post]]
  (testing "supports paging via include_total"
    (let [expected (store-example-resources)]
      (doseq [[label count?] [["without" false]
                              ["with" true]]]
        (testing (str "should support paging through nodes " label " counts")
          (let [results (paged-results
                         {:app-fn  *app*
                          :path    endpoint
                          :limit   2
                          :total   (count expected)
                          :params {:order_by (json/generate-string
                                              [{:field :certname
                                                :order :desc}
                                               {:field :type
                                                :order :desc}
                                               {:field :title
                                                :order :desc}])}
                          :include_total  count?})]
            (is (= (count results) (count expected)))
            (is (= (set (vals expected))
                   (set results)))))))))

(deftest-http-app resource-query-result-ordering
  [[version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 foo2 bar1 bar2] :as expected} (store-example-resources)]
    (testing "ordering results with order_by"
      (let [params {:order_by (vector-param method [{"field" "certname" "order" "DESC"}
                                                    {"field" "resource" "order" "DESC"}])}
            response (query-response method endpoint nil params)
            actual   (json/parse-string (slurp (get response :body "null")) true)]
        (is (= http/status-ok (:status response)))
        (is (= actual [bar2 bar1 foo2 foo1]))))))

(deftest-http-app query-environments
  [[version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]
    (testing "querying by equality and regexp should be allowed"
      (are [query] (is (= (query-result (query-response method endpoint query)) #{foo1 foo2}))
           ["=" "environment" "DEV"]
           ["~" "environment" ".*V"]
           ["not" ["~" "environment" "PR.*"]]
           ["not" ["=" "environment" "PROD"]])
      (are [query] (is (= (query-result (query-response method endpoint query)) #{bar1 bar2}))
           ["=" "environment" "PROD"]
           ["~" "environment" "PR.*"]
           ["not" ["=" "environment" "DEV"]])
      (are [query] (is (= (query-result (query-response method endpoint query)) #{foo1 foo2 bar1 bar2}))
           ["not" ["=" "environment" "null"]]))))

(deftest-http-app query-with-projection
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]
    (testing "querying by equality and regexp should be allowed"
      (are [query expected] (is-response-equal
                              (query-response method endpoint query) expected)
           ["extract" "type"
            ["=" "environment" "DEV"]]
           #{{:type (:type foo1)}
             {:type (:type foo2)}}

           ["extract" [["function" "count"] "type"]
            ["=" "environment" "DEV"]
            ["group_by" "type"]]
           #{{:type "File" :count 1}
             {:type "Notify" :count 1}}))))

(deftest-http-app paging-results
  [[version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 4]]]
        (let [results (ordered-query-result method endpoint
                                            nil
                                            {:limit limit})]
          (is (= expected (count results))))))

    (testing "offset results"
      (doseq [[order offset expected] [["asc" 0 [foo1 bar1 foo2 bar2]]
                                       ["asc" 1 [bar1 foo2 bar2]]
                                       ["asc" 2 [foo2 bar2]]
                                       ["asc" 3 [bar2]]
                                       ["asc" 4 []]
                                       ["desc" 0 [bar2 foo2 bar1 foo1]]
                                       ["desc" 1 [foo2 bar1 foo1]]
                                       ["desc" 2 [bar1 foo1]]
                                       ["desc" 3 [foo1]]
                                       ["desc" 4 []]]]
        (testing order
          (let [actual (ordered-query-result method endpoint
                                             nil
                                             {:order_by (vector-param method
                                                                      [{"field" "title"
                                                                        "order" order}
                                                                       {"field" "certname"
                                                                        "order" order}])
                                              :offset offset})]
            (is (= actual expected))))))))

(deftest-http-app query-null-environments
  [[version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources false)]
    (testing "querying by equality and regexp should be allowed"
      (is (is-response-equal (query-response method endpoint ["=" "type" "File"]) #{foo1 bar1}))
      (is (is-response-equal (query-response method endpoint ["=" "type" "Notify"]) #{foo2 bar2})))))

(def versioned-invalid-queries
  (omap/ordered-map
    "/v4/resources" (omap/ordered-map
                      ;; inequality operator with string
                      ["<" "line" "22"]
                      #"Argument \"22\" and operator \"<\" have incompatible types."
                      ;; Top level extract using invalid fields should throw an error
                      ["extract" "nothing" ["~" "certname" ".*"]]
                      #"Can't extract unknown 'resources' field 'nothing'.*Acceptable fields are.*"

                      ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
                      #"Can't extract unknown 'resources' fields: 'nothing', 'nothing2'.*Acceptable fields are.*")))

(deftest-http-app invalid-queries
  [[version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] (get versioned-invalid-queries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers] :as result} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))
        (is (= headers {"Content-Type" http/error-response-content-type}))))))
