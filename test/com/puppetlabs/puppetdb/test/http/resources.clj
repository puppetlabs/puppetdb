(ns com.puppetlabs.puppetdb.test.http.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [puppetlabs.kitchensink.core :as ks]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [com.puppetlabs.puppetdb.query :refer [remove-environment]]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [com.puppetlabs.puppetdb.testutils :refer [get-request paged-results
                                                       deftestseq]]
            [com.puppetlabs.puppetdb.testutils.resources :refer [store-example-resources]]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize]]))

(def v2-endpoint "/v2/resources")
(def v3-endpoint "/v3/resources")
(def v4-endpoint "/v4/resources")
(def v4-environments-endpoint "/v4/environments/DEV/resources")

(def endpoints [[:v2 v2-endpoint]
                [:v3 v3-endpoint]
                [:v4 v4-endpoint]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  ([endpoint]              (get-response endpoint nil))
  ([endpoint query]        (get-response endpoint query {}))
  ([endpoint query params]
   (let [resp (fixt/*app* (get-request endpoint query params))]
     (if (string? (:body resp))
       resp
       (update-in resp [:body] slurp)))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok (:status response)))
  (is (= pl-http/json-response-content-type (tu/content-type response)))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil))))

(defn v3->v2-results
  "Munge example resource output from v3 API format to v2 format"
  [example-resources]
  (ks/mapvals #(clojure.set/rename-keys % {:file :sourcefile :line :sourceline}) example-resources))

(defn v4->v3-results
  "Munge example resource output from v4 API to v3"
  [example-resources]
  (ks/mapvals #(remove-environment % :v3) example-resources))

(defn v4->v2-results
  "Munge the example resource output from v4 to v2"
  [example-resources]
  (-> example-resources v4->v3-results v3->v2-results))

(deftestseq resource-endpoint-tests
  [[version endpoint] endpoints]

  (let [store-result (store-example-resources)
        result (case version
                 :v2 (v4->v2-results store-result)
                 :v3 (v4->v3-results store-result)
                 store-result)
        {:keys [foo1 bar1 foo2 bar2] :as expected} result]
    (testing "query without filter should not fail"
      (let [response (get-response endpoint)
            body     (get response :body "null")]
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
        (is-response-equal (get-response endpoint query) result)))

    (testing "only v4 or after queries"
      (when-not (contains? #{:v2 :v3} version)
        (doseq [[query result] [[["~" ["parameter" "owner"] "ro.t"] #{foo1 bar1}]
                                [["not" ["~" ["parameter" "owner"] "ro.t"]] #{foo2 bar2}]]]
          (is-response-equal (get-response endpoint query) result))))

    (testing "fact subqueries are supported"
      (let [{:keys [body status]} (get-response endpoint
                                                ["and"
                                                 ["=" "type" "File"]
                                                 ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                         ["and"
                                                                                          ["=" "name" "operatingsystem"]
                                                                                          ["=" "value" "Debian"]]]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo1})))

      ;; Using the value of a fact as the title of a resource
      (let [{:keys [body status]} (get-response endpoint
                                                ["in" "title" ["extract" "value" ["select-facts"
                                                                                  ["=" "name" "message"]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

    (testing "resource subqueries are supported"
      ;; Fetch exported resources and their corresponding collected versions
      (let [{:keys [body status]} (get-response endpoint
                                                ["or"
                                                 ["=" "exported" true]
                                                 ["and"
                                                  ["=" "exported" false]
                                                  ["in" "title" ["extract" "title" ["select-resources"
                                                                                    ["=" "exported" true]]]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo2 bar2}))))

    (testing "error handling"
      (let [response (get-response endpoint ["="])
            body     (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
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
        (is-response-equal (get-response endpoint query) result)))))

(deftestseq environments-resource-endpoint
  [[version endpoint] endpoints
   :when (not-any? #(= version %) [:v2 :v3])]
  (let [{:keys [foo1 bar1 foo2 bar2] :as results} (store-example-resources)
        dev-endpoint (str "/" (name version) "/environments/DEV/resources")
        prod-endpoint (str "/" (name version) "/environments/PROD/resources")]

    (doseq [endpoint [dev-endpoint prod-endpoint]]
      (testing (str "query without filter should not fail for endpoint " endpoint)
        (let [response (get-response endpoint)
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
        (is-response-equal (get-response dev-endpoint query) result)))

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
        (is-response-equal (get-response prod-endpoint query) result)))))

(deftestseq query-sourcefile-sourceline
  [[version endpoint] endpoints]

  (let [{:keys [bar2] :as results}
        (case version
          :v2 (v4->v2-results (store-example-resources))
          :v3 (v4->v3-results (store-example-resources))
          (store-example-resources))]

    (when (= version :v2)
      (testing "sourcefile and sourceline is queryable"
        (are [query] (is-response-equal (get-response endpoint query) #{bar2})
          ["=" "sourcefile" "/foo/bar"]
          ["~" "sourcefile" "foo"]
          ["=" "sourceline" 22]))

      (testing "querying by file and line is not supported"
        (let [query ["=" "line" 22]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (= "line is not a queryable object for resources" (:body response))))
        (let [query ["~" "file" "foo"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (= "file cannot be the target of a regexp match" (:body response))))
        (let [query ["=" "file" "/foo/bar"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (= "file is not a queryable object for resources" (:body response))))))

    (when (= version :v3)
      (testing "sourcefile and source is not supported"
        (let [query ["=" "sourceline" 22]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourceline' is not a queryable object for resources" (:body response))))
        (let [query ["~" "sourcefile" "foo"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourcefile' cannot be the target of a regexp match" (:body response))))
        (let [query ["=" "sourcefile" "/foo/bar"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourcefile' is not a queryable object for resources" (:body response)))))

      (testing "query by file and line is supported"
        (let [query ["=" "file" "/foo/bar"]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))
        (let [query ["~" "file" "foo"]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))
        (let [query ["=" "line" 22]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))))

    (when-not (contains? #{:v2 :v3} version)
      (testing "sourcefile and source is not supported"
        (let [query ["=" "sourceline" 22]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourceline' is not a queryable object for resources, known queryable objects are" (:body response))))
        (let [query ["~" "sourcefile" "foo"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourcefile' is not a queryable object for resources, known queryable objects are" (:body response))))
        (let [query ["=" "sourcefile" "/foo/bar"]
              response (get-response endpoint query)]
          (is (= pl-http/status-bad-request (:status response)))
          (is (re-find #"'sourcefile' is not a queryable object for resources, known queryable objects are" (:body response)))))

      (testing "query by file and line is supported"
        (let [query ["=" "file" "/foo/bar"]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))
        (let [query ["~" "file" "foo"]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))
        (let [query ["=" "line" 22]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))

        (let [query ["and"
                     [">" "line" 21]
                     ["<" "line" 23]]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))
        (let [query ["and"
                     [">" "line" "21"]
                     ["<" "line" "23"]]
              result #{bar2}]
          (is-response-equal (get-response endpoint query) result))))))

(deftestseq resource-query-paging
  [[version endpoint] endpoints]

  (when (= version :v2)
    (testing "does not support paging-related query parameters"
      (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
        (let [{:keys [status body]} (get-response v2-endpoint nil {k v})]
          (is (= status pl-http/status-bad-request))
          (is (= body (format "Unsupported query parameter '%s'" (name k))))))))

  (when (not= version :v2)
    (testing "supports paging via include-total"
      (let [expected
            (case version
              :v3 (v4->v3-results (store-example-resources))
              (store-example-resources))]
        (doseq [[label count?] [["without" false]
                                ["with" true]]]
          (testing (str "should support paging through nodes " label " counts")
            (let [results (paged-results
                           {:app-fn  fixt/*app*
                            :path    endpoint
                            :limit   2
                            :total   (count expected)
                            :include-total  count?})]
              (is (= (count results) (count expected)))
              (is (= (set (vals expected))
                     (set results))))))))))

(deftestseq resource-query-result-ordering
  [[version endpoint] endpoints
   :when (not= version :v2)]

  (let [{:keys [foo1 foo2 bar1 bar2] :as expected}
        (case version
          :v3 (v4->v3-results (store-example-resources))
          (store-example-resources))]
    (testing "ordering results with order-by"
      (let [order-by {:order-by (json/generate-string [{"field" "certname" "order" "DESC"}
                                                       {"field" "resource" "order" "DESC"}])}
            response (get-response endpoint nil order-by)
            actual   (json/parse-string (get response :body "null") true)]
        (is (= pl-http/status-ok (:status response)))
        (is (= actual [bar2 bar1 foo2 foo1]))))))

(deftestseq query-environments
  [[version endpoint] endpoints]

  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources)]
    (when (not-any? #(= version %) [:v2 :v3])
      (testing "querying by equality and regexp should be allowed"
        (are [query] (is-response-equal (get-response endpoint query) #{foo1 foo2})
          ["=" "environment" "DEV"]
          ["~" "environment" ".*V"]
          ["not" ["~" "environment" "PR.*"]]
          ["not" ["=" "environment" "PROD"]])
        (are [query] (is-response-equal (get-response endpoint query) #{bar1 bar2})
          ["=" "environment" "PROD"]
          ["~" "environment" "PR.*"]
          ["not" ["=" "environment" "DEV"]])
        (are [query] (is-response-equal (get-response endpoint query) #{foo1 foo2 bar1 bar2})
          ["not" ["=" "environment" "null"]])))

    (when (some #(= version %) [:v2 :v3])
      (testing "querying environment not allowed"
        (let [response (get-response endpoint ["=" "environment" "DEV"])]
          (is (re-find #"'environment' is not a queryable.*" (:body response)))
          (is (= 400 (:status response))))
        (let [response (get-response endpoint ["~" "environment" "DEV"])]
          (is (re-find #"'environment' cannot be the target.*version 3*" (:body response)))
          (is (= 400 (:status response))))))))

(deftestseq query-null-environments
  [[version endpoint] endpoints
   :when (not-any? #(= version %) [:v2 :v3])]

  (let [{:keys [foo1 foo2 bar1 bar2]} (store-example-resources false)]
    (testing "querying by equality and regexp should be allowed"
      (is (is-response-equal (get-response endpoint ["=" "type" "File"]) #{foo1 bar1}))
      (is (is-response-equal (get-response endpoint ["=" "type" "Notify"]) #{foo2 bar2})))))
