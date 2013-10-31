(ns com.puppetlabs.puppetdb.test.http.v1.resources
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.http :as pl-http]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.scf.storage :only [deactivate-node!]]
        [com.puppetlabs.puppetdb.scf.storage-utils :only [db-serialize to-jdbc-varchar-array]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

;;;; Test the resource listing handlers.
(def c-t pl-http/json-response-content-type)

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (if (string? query) query (json/generate-string query))})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (let [resp (*app* (get-request "/v1/resources" query))]
             (if (string? (:body resp))
               resp
               (update-in resp [:body] slurp)))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil)) (str response)))

(deftest resource-list-handler
  (with-transacted-connection *db*
    (sql/insert-records
     :resource_params_cache
     {:resource "1" :parameters (db-serialize {"ensure" "file"
                                               "owner"  "root"
                                               "group"  "root"
                                               "acl"    ["john:rwx" "fred:rwx"]})}
     {:resource "2" :parameters nil})
    (sql/insert-records
     :resource_params
     {:resource "1" :name "ensure" :value (db-serialize "file")}
     {:resource "1" :name "owner"  :value (db-serialize "root")}
     {:resource "1" :name "group"  :value (db-serialize "root")}
     {:resource "1" :name "acl"    :value (db-serialize ["john:rwx" "fred:rwx"])})
    (sql/insert-records
     :certnames
     {:name "one.local"}
     {:name "two.local"})
    (sql/insert-records
     :catalogs
     {:hash "foo" :api_version 1 :catalog_version "12"}
     {:hash "bar" :api_version 1 :catalog_version "14"})
    (sql/insert-records
     :certname_catalogs
     {:certname "one.local" :catalog "foo"}
     {:certname "two.local" :catalog "bar"})
    (sql/insert-records :catalog_resources
                        {:catalog "foo" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array ["one" "two"])}
                        {:catalog "bar" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array ["one" "two"])}
                        {:catalog "bar" :resource "2" :type "Notify" :title "hello" :exported true :file "/foo/bar" :line 22 :tags (to-jdbc-varchar-array [])}))

  (let [foo1 {:certname   "one.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       ["one" "two"]
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {:ensure "file"
                           :owner  "root"
                           :group  "root"
                           :acl    ["john:rwx" "fred:rwx"]}}
        bar1 {:certname   "two.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       ["one" "two"]
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {:ensure "file"
                           :owner  "root"
                           :group  "root"
                           :acl    ["john:rwx" "fred:rwx"]}}
        bar2 {:certname   "two.local"
              :resource   "2"
              :type       "Notify"
              :title      "hello"
              :tags       []
              :exported   true
              :sourcefile "/foo/bar"
              :sourceline 22
              :parameters {}}]

    (testing "query without filter"
      (let [response (get-response)
            body     (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (= "Missing required query parameter 'query'" body))))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["and"
                                ["=" ["node" "name"] "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["or"
                                ["=" ["node" "name"] "one.local"]
                                ["=" "type" "File"]]
                               #{foo1 bar1}]
                              [["not"
                                ["=" ["node" "name"] "one.local"]
                                ["=" "type" "File"]]
                               #{bar2}]
                              [["=" ["parameter" "ensure"] "file"] #{foo1 bar1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1 bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is-response-equal (get-response query) result)))

    (testing "query by source file"
      (let [query ["=" "sourcefile" "/foo/bar"]
            result #{bar2}]
        (is-response-equal (get-response query) result)))

    (testing "query by source line"
      (let [query ["=" "sourceline" 22]
            result #{bar2}]
        (is-response-equal (get-response query) result)))

    (testing "query by new field names file/line"
      (let [query ["=" "line" 22]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "line is not a queryable object for resources" (:body response))))
      (let [query ["~" "file" "foo"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "[\"~\" \"file\" \"foo\"] is not well-formed: query operator '~' is unknown" (:body response))))
      (let [query ["=" "file" "/foo/bar"]
            response (get-response query)]
        (is (= pl-http/status-bad-request (:status response)))
        (is (= "file is not a queryable object for resources" (:body response)))))

    (testing "querying against inactive nodes"
      (deactivate-node! "one.local")

      (testing "should exclude inactive nodes when requested"
        (let [query ["=" ["node" "active"] true]
              result #{bar1 bar2}]
          (is-response-equal (get-response query) result)))

      (testing "should exclude active nodes when requested"
        (let [query ["=" ["node" "active"] false]
              result #{foo1}]
          (is-response-equal (get-response query) result)))

      (testing "should include all nodes otherwise"
        (let [query ["=" "type" "File"]
              result #{foo1 bar1}]
          (is-response-equal (get-response query) result))))

    (testing "fact subqueries are unsupported"
      (let [{:keys [body status]} (get-response ["and"
                                                 ["=" "type" "File"]
                                                 ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                                ["and"
                                                                                                 ["=" "name" "operatingsystem"]
                                                                                                 ["=" "value" "Debian"]]]]]])]
        (is (= status pl-http/status-bad-request))
        (is (re-find #"Operator .* is not available in v1 resource queries" body)))))
    (testing "error handling"
      (let [response (get-response ["="])
            body     (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"= requires exactly two arguments" body)))))
