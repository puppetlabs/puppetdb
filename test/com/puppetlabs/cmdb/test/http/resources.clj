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
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize
                                                initialize-store]]))

(def *app* nil)

(use-fixtures :each (fn [f]
                      (let [db (test-db)]
                        (binding [*app* (server/build-app {:scf-db db})]
                          (sql/with-connection db
                            (initialize-store)
                            (f))))))

;;;; Test the resource listing handlers.
(def *c-t*   r/resource-list-c-t)

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

(defmacro is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  `(let [response# ~response]
     (is (= 200   (:status response#)))
     (is (= *c-t* (get-in response# [:headers "Content-Type"])))
     (is (= ~body (if (:body response#)
                    (json/parse-string (:body response#) true)
                    nil)) (str response#))))


(deftest resource-list-handler
  (sql/insert-records
   :resources
   {:hash "1" :type "File"   :title "/etc/passwd"   :exported true}
   {:hash "2" :type "Notify" :title "hello"         :exported true})
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
  (doseq [n (range 1 3)]
    (sql/insert-record :catalog_resources
                       {:catalog "foo" :resource (str n)}))
  (doseq [n [2]]
    (sql/insert-record :catalog_resources
                       {:catalog "bar" :resource (str n)}))
  (sql/insert-records
   :resource_tags
   {:resource "1" :name "one"}
   {:resource "1" :name "two"})

  (testing "query without filter"
    (is-response-equal (get-response)
                       [{:hash       "1"
                         :type       "File"
                         :title      "/etc/passwd"
                         :exported   true
                         :sourcefile nil
                         :sourceline nil
                         :parameters {:ensure "file"
                                      :owner  "root"
                                      :group  "root"
                                      :acl    ["john:rwx" "fred:rwx"]}}
                        {:hash       "2"
                         :type       "Notify"
                         :title      "hello"
                         :exported   true
                         :sourcefile nil
                         :sourceline nil}]))
  (testing "query with filter"
    (doseq [query [["=" "type" "File"]
                   ["=" "tag" "one"]
                   ["=" "tag" "two"]
                   ["and" ["=" ["node" "certname"] "one.local"] ["=" "type" "File"]]
                   ["=" ["parameter" "ensure"] "file"]
                   ["=" ["parameter" "owner"]  "root"]
                   ["=" ["parameter" "acl"]    ["john:rwx" "fred:rwx"]]]]
      (is-response-equal (get-response query)
                         [{:hash       "1"
                           :type       "File"
                           :title      "/etc/passwd"
                           :exported   true
                           :sourcefile nil
                           :sourceline nil
                           :parameters {:ensure "file"
                                        :owner  "root"
                                        :group  "root"
                                        :acl    ["john:rwx" "fred:rwx"]}}])))
  (testing "error handling"
    (let [response (get-response ["="])
          body     (json/parse-string (get response :body "null") true)]
      (is (= (:status response) 400))
      (is (re-find #"operators take two arguments" (:error body))))))
