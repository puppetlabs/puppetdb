(ns com.puppetlabs.puppetdb.test.http.v2.resources
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.http :as pl-http]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize to-jdbc-varchar-array add-facts! deactivate-node!]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

;;;; Test the resource listing handlers.
(def c-t "application/json")

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
  ([query] (*app* (get-request "/v2/resources" query))))

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
    (add-facts! "one.local"
                {"operatingsystem" "Debian"
                 "kernel" "Linux"
                 "uptime_seconds" 50000}
                (now))
    (add-facts! "two.local"
                {"operatingsystem" "Ubuntu"
                 "kernel" "Linux"
                 "uptime_seconds" 10000
                 "message" "hello"}
                (now))
    (sql/insert-records :catalog_resources
                        {:catalog "foo" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array ["one" "two"])}
                        {:catalog "bar" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array ["one" "two"])}
                        {:catalog "bar" :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array [])}))

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
              :sourcefile nil
              :sourceline nil
              :parameters {}}]

    (testing "query without filter"
      (let [response (get-response)
            body     (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find #"missing query" body))))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["and"
                                ["=" ["node" "name"] "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["=" ["parameter" "ensure"] "file"] #{foo1 bar1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1 bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is-response-equal (get-response query) result)))

    (testing "query exceeding resource-query-limit"
      (with-http-app {:resource-query-limit 1}
        (fn []
          (let [response (get-response ["=" "type" "File"])
                body     (get response :body "null")]
            (is (= (:status response) pl-http/status-internal-error))
            (is (re-find #"more than the maximum number of results" body))))))

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

    (testing "fact subqueries are supported"
      (let [{:keys [body status]} (get-response ["and"
                                                 ["=" "type" "File"]
                                                 ["in-result" ["node" "certname"] ["project" "node" ["select-facts"
                                                                                                     ["and"
                                                                                                      ["=" ["fact" "name"] "operatingsystem"]
                                                                                                      ["=" ["fact" "value"] "Debian"]]]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{foo1})))

      ;; Using the value of a fact as the title of a resource
      (let [{:keys [body status]} (get-response ["in-result" ["resource" "title"] ["project" "value" ["select-facts"
                                                                                                      ["=" ["fact" "name"] "message"]]]])]
        (is (= status pl-http/status-ok))
        (is (= (set (json/parse-string body true)) #{bar2})))))

  (testing "error handling"
    (let [response (get-response ["="])
          body     (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"= requires exactly two arguments" body)))))
