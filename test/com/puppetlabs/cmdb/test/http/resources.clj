(ns com.puppetlabs.cmdb.test.http.resources
  (:require [com.puppetlabs.cmdb.http.resources :as r]
            [com.puppetlabs.cmdb.http.server :as server]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [clojure.contrib.duck-streams :only (read-lines)]
        [com.puppetlabs.cmdb.testutils :only [test-db]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize to-jdbc-varchar-array]]
        [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def *app* nil)

(use-fixtures :each (fn [f]
                      (let [db (test-db)]
                        (binding [*app* (server/build-app {:scf-db db})]
                          (sql/with-connection db
                            (migrate!)
                            (f))))))

;;;; Test the resource listing handlers.
(def *c-t* "application/json")

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (if (string? query) query (json/generate-string query))})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" *c-t*)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/resources" query))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= 200   (:status response)))
  (is (= *c-t* (get-in response [:headers "Content-Type"])))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil)) (str response)))


(deftest resource-list-handler
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
    {:catalog "bar" :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array [])})
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
      (is-response-equal (get-response) #{foo1 bar1 bar2}))

    (testing "query with filter"
      (doseq [[query result] [[["=" "type" "File"] #{foo1 bar1}]
                              [["=" "tag" "one"] #{foo1 bar1}]
                              [["=" "tag" "two"] #{foo1 bar1}]
                              [["and"
                                ["=" ["node" "certname"] "one.local"]
                                ["=" "type" "File"]]
                               #{foo1}]
                              [["=" ["parameter" "ensure"] "file"] #{foo1 bar1}]
                              [["=" ["parameter" "owner"] "root"] #{foo1 bar1}]
                              [["=" ["parameter" "acl"] ["john:rwx" "fred:rwx"]] #{foo1 bar1}]]]
        (is-response-equal (get-response query) result))))

  (testing "error handling"
    (let [response (get-response ["="])
          body     (get response :body "null")]
      (is (= (:status response) 400))
      (is (re-find #"operators take two arguments" body)))))
