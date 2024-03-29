(ns puppetlabs.puppetdb.http.resources-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [clojure.set :as set]
            [puppetlabs.puppetdb.testutils :as tu :refer [paged-results]]
            [puppetlabs.puppetdb.testutils.resources :refer [store-example-resources]]
            [puppetlabs.puppetdb.testutils.http
             :refer [*app*
                     are-error-response-headers
                     deftest-http-app
                     query-response
                     ordered-query-result
                     vector-param]]
            [flatland.ordered.map :as omap])
  (:import
   (java.net HttpURLConnection)))

(def v4-endpoint "/v4/resources")
(def v4-environments-endpoint "/v4/environments/DEV/resources")

(def endpoints [[:v4 v4-endpoint]])

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= HttpURLConnection/HTTP_OK (:status response)))
  (is (http/json-utf8-ctype? (tu/content-type response)))
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
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [foo1 bar1 foo2 bar2]} (store-example-resources)
        all-resources (set [foo1 bar1 foo2 bar2])]
    (testing "query without filter should not fail"
      (let [response (query-response method endpoint)
            result (query-result response)]
        (is (= all-resources result))
        (is (= 200 (:status response)))))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["=" "tag" "æøåۿᚠ𠜎٤"] #{foo1}]
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
                              [["=" "parameters.nested.foo" "bar"] #{foo1 bar1}]
                              [["=" "parameters.boolean" true] #{foo1 bar1}]
                              [["=" "parameters.numeric" 1337] #{foo1 bar1}]
                              [["=" "parameters.double" 3.14] #{foo1 bar1}]
                              [["=" "parameters.acl" ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is (= (query-result (query-response method endpoint query)) result))))

    (testing "dot-style querying for regex resource parameters"
      (doseq [[query result] [[["~" "parameters.owner" "oot"] #{foo1 bar1}]
                              [["~" "parameters.owner" "^root"] #{foo1 bar1}]
                              [["~" "parameters.owner" "oot$"] #{foo1 bar1}]
                              [["~" "parameters.nested.foo" "ar"] #{foo1 bar1}]
                              [["~" "parameters.nested.foo" "^bar$"] #{foo1 bar1}]
                              [["~" "parameters.double_quote" "^foo\"bar$"] #{foo1 bar1}]
                              [["~" "parameters.backslash" "^foo\\\\bar$"] #{foo1 bar1}]]]
        (testing query
          (is (= (query-result (query-response method endpoint query)) result)))

        (testing ["not" query]
          (is (= (query-result (query-response method endpoint ["not" query]))
                 (set/difference all-resources result))))))

    (testing "null? operator on dotted paths"
      (doseq [[query result] [[["null?" "parameters.path.doesnt.exist" true] #{}]
                              [["null?" "parameters.path.doesnt.exist" false] #{}]
                              [["null?" "parameters.ensure" false] #{foo1 bar1}]]]
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
        (is (= status HttpURLConnection/HTTP_OK))
        (is (= (set (json/parse-string (slurp body) true)) #{foo1})))

      (testing "using the value of a fact as the title of a resource"
        (let [{:keys [body status]} (query-response method endpoint
                                                    ["in" "title" ["extract" "value" ["select_facts"
                                                                                      ["=" "name" "message"]]]])]
          (is (= status HttpURLConnection/HTTP_OK))
          (is (= (set (json/parse-string (slurp body) true)) #{foo2 bar2})))))

    (testing "resource subqueries are supported"
      ;; Fetch exported resources and their corresponding collected versions
      (let [{:keys [body status]} (query-response method endpoint
                                                ["or"
                                                 ["=" "exported" true]
                                                 ["and"
                                                  ["=" "exported" false]
                                                  ["in" "title" ["extract" "title" ["select_resources"
                                                                                    ["=" "exported" true]]]]]])]
        (is (= status HttpURLConnection/HTTP_OK))
        (is (= (set (json/parse-string (slurp body) true)) #{foo2 bar2}))))

    (testing "error handling"
      (let [response (query-response method endpoint ["="])
            body     (get response :body "null")]
        (is (= (:status response) HttpURLConnection/HTTP_BAD_REQUEST))
        (are-error-response-headers (:headers response))
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

(deftest-http-app query-with-explain-printing
  [[_version endpoint] endpoints
   method [:get :post]]
    (testing "should support explain and not munge rows when quering in resources endpoint"
      (let [results (json/parse-string
                    (slurp (:body (query-response method endpoint nil
                                                  {:explain "analyze"}))))]
        (is (seq results))
        (is (= true (contains? (first results) "query plan"))))))

(deftest-http-app environments-resource-endpoint
  [[version _endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 bar1 foo2 bar2]} (store-example-resources)
        dev-endpoint (str "/" (name version) "/environments/DEV/resources")
        prod-endpoint (str "/" (name version) "/environments/PROD/resources")]

    (doseq [[endpoint expected] [[dev-endpoint [foo1 foo2]]
                                 [prod-endpoint [bar1 bar2]]]]
      (testing (str "query without filter should not fail for endpoint " endpoint)
        (let [response (query-response method endpoint)
              result (query-result response)]
          (is (= (set expected) (set result)))
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
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [bar2]} (store-example-resources)]

    (testing "sourcefile and source is not supported"
      (let [query ["=" "sourceline" 22]
            response (query-response method endpoint query)]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"'sourceline' is not a queryable object for resources. Known queryable objects are" (:body response))))
      (let [query ["~" "sourcefile" "foo"]
            response (query-response method endpoint query)]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"'sourcefile' is not a queryable object for resources. Known queryable objects are" (:body response))))
      (let [query ["=" "sourcefile" "/foo/bar"]
            response (query-response method endpoint query)]
        (is (= HttpURLConnection/HTTP_BAD_REQUEST (:status response)))
        (are-error-response-headers (:headers response))
        (is (re-find #"'sourcefile' is not a queryable object for resources. Known queryable objects are" (:body response)))))

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
  [[_version endpoint] endpoints
   method [:get :post]]
  (testing "supports paging via include_total"
    (let [expected (store-example-resources)]
      (doseq [[label count?] [["without" false]
                              ["with" true]]]
        (testing (str "should support paging through nodes " label " counts")
          (let [results (paged-results
                         method
                         {:app-fn  *app*
                          :path    endpoint
                          :limit   2
                          :total   (count expected)
                          :params {:order_by (->> [{:field :certname
                                                    :order :desc}
                                                   {:field :type
                                                    :order :desc}
                                                   {:field :title
                                                    :order :desc}]
                                                  (vector-param method))}
                          :include_total  count?})]
            (is (= (count results) (count expected)))
            (is (= (set (vals expected))
                   (set results)))))))))

(deftest-http-app resource-query-result-ordering
  [[_version endpoint] endpoints
   method [:get :post]]
  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]
    (testing "ordering results with order_by"
      (let [params {:order_by (vector-param method [{"field" "certname" "order" "DESC"}
                                                    {"field" "resource" "order" "DESC"}])}
            response (query-response method endpoint nil params)
            actual   (json/parse-string (slurp (get response :body "null")) true)]
        (is (= HttpURLConnection/HTTP_OK (:status response)))
        (is (= actual [bar2 bar1 foo2 foo1]))))))

(deftest-http-app query-environments
  [[_version endpoint] endpoints
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
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [foo1 foo2]} (store-example-resources)]
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
             {:type "Notify" :count 1}}

           ["extract" ["certname" "parameters.ensure"]
            ["=" "type" "File"]]
           #{{:certname "one.local" :parameters.ensure "file"}
             {:certname "two.local" :parameters.ensure "file"}}))))

(deftest-http-app paging-results
  [[_version endpoint] endpoints
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
  [[_version endpoint] endpoints
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
                      #"Can't extract unknown 'resources' fields 'nothing' and 'nothing2'.*Acceptable fields are.*")))

(deftest-http-app invalid-queries
  [[_version endpoint] endpoints
   method [:get :post]]

  (doseq [[query msg] (get versioned-invalid-queries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)))))
