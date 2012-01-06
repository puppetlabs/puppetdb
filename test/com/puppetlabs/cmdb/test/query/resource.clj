(ns com.puppetlabs.cmdb.test.query.resource
  (:require [com.puppetlabs.cmdb.query.resource :as s]
            [com.puppetlabs.cmdb.query :as query]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [clojure.contrib.duck-streams :only (read-lines)]
        [com.puppetlabs.cmdb.testutils :only [test-db]]
        [com.puppetlabs.cmdb.scf.storage :only [to-jdbc-varchar-array
                                                initialize-store
                                                sql-array-query-string]]))

(def *db* nil)
(def *app* nil)

(use-fixtures :each (fn [f]
                      (let [db (test-db)]
                        (binding [*db* db
                                  *app* (query/build-app db)]
                          (sql/with-connection db
                            (initialize-store)
                            (f))))))

(deftest query->sql
  (testing "comparisons"
    ;; simple, local attributes
    (is (= (s/query->sql ["=" "title" "whatever"])
           ["(SELECT DISTINCT hash FROM resources WHERE title = ?)" "whatever"]))
    ;; with a path to the field
    (let [[sql & params] (s/query->sql ["=" ["node" "certname"] "example"])]
      (is (= params ["example"]))
      (is (re-find #"JOIN certname_resources" sql))
      (is (re-find #"WHERE certname_resources.certname = \?" sql)))
    (let [[sql & params] (s/query->sql ["=" "tag" "foo"])]
      (is (re-find #"SELECT DISTINCT hash FROM resources" sql))
      (is (re-find #"JOIN resource_tags" sql))
      (is (= params ["foo"]))))
  (testing "order of params in grouping"
    (let [[sql & params] (s/query->sql ["and"
                                        ["=" "type" "foo"]
                                        ["=" "type" "bar"]
                                        ["=" "type" "baz"]])]
      (is (= params ["foo" "bar" "baz"]))))
  (let [terms [["=" "title" "one"]
               ["=" "type" "two"]
               ["=" "tag" "three"]
               ["=" ["node" "certname"] "four"]
               ["=" ["parameter" "ensure"] "five"]
               ["=" ["parameter" "banana"] "yumm"]]]
    (testing "simple {and, or} grouping"
      (doall
       (for [[op join] {"and" "INTERSECT" "or" "UNION"}
             one terms two terms]
         (let [[sql1 & param1] (s/query->sql one)
               [sql2 & param2] (s/query->sql two)
               [sql & params] (s/query->sql [op one two])]
           (is (= sql (str "(" sql1 " " join " " sql2 ")")))
           (is (= params (concat param1 param2)))))))
    (testing "simple {and, or} grouping with many terms"
      (doall
       (for [[op join] {"and" " INTERSECT " "or" " UNION "}]
         (let [terms-  (map s/query->sql terms)
               sql-    (str "(" (string/join join (map first terms-)) ")")
               params- (reduce concat (map rest terms-))
               [sql & params] (s/query->sql (apply (partial vector op) terms))]
           (is (= sql sql-))
           (is (= params params-)))))))
  (testing "negation"
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]])]
      (is (= sql (str "(SELECT DISTINCT hash FROM resources EXCEPT "
                      "((SELECT DISTINCT hash FROM resources WHERE type = ?)))")))
      (is (= params ["foo"])))
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]
                                        ["=" "title" "bar"]])]
      (is (= sql (str "(SELECT DISTINCT hash FROM resources EXCEPT "
                      "("
                      "(SELECT DISTINCT hash FROM resources WHERE type = ?)"
                      " UNION "
                      "(SELECT DISTINCT hash FROM resources WHERE title = ?)"
                      ")"
                      ")")))
      (is (= params ["foo" "bar"]))))
  (testing "real world query"
    (let [[sql & params]
          (s/query->sql ["and"
                         ["not" ["=" ["node" "certname"] "example.local"]]
                         ["=" "exported" true]
                         ["=" ["parameter" "ensure"] "yellow"]])]
      (is (= sql (str "("
                      ;; top level and not certname
                      "(SELECT DISTINCT hash FROM resources EXCEPT ("
                      "(SELECT DISTINCT hash FROM resources JOIN certname_resources "
                      "ON certname_resources.resource = resources.hash "
                      "WHERE certname_resources.certname = ?)"
                      "))"
                      ;; exported
                      " INTERSECT "
                      "(SELECT DISTINCT hash FROM resources "
                      "WHERE exported = ?)"
                      ;; parameter match
                      " INTERSECT "
                      "(SELECT DISTINCT hash FROM resources JOIN resource_params "
                      "ON resource_params.resource = resources.hash "
                      "WHERE ? = resource_params.name AND "
                      (sql-array-query-string "resource_params.value")
                      ")"
                      ")")))
      (is (= params ["example.local" true "ensure" "yellow"])))))


;; now, for some end-to-end testing with a database...
(deftest query-resources
  (sql/insert-records
   :resources
   {:hash "1" :type "File"   :title "/etc/passwd"   :exported true}
   {:hash "2" :type "Notify" :title "hello"         :exported true}
   {:hash "3" :type "Notify" :title "no-params"     :exported true}
   {:hash "4" :type "File"   :title "/etc/Makefile" :exported false}
   {:hash "5" :type "Notify" :title "booyah"        :exported false}
   {:hash "6" :type "Mval"   :title "multivalue"    :exported false})
  (sql/insert-records
   :resource_params
   {:resource "1" :name "ensure"  :value (to-jdbc-varchar-array ["file"])}
   {:resource "1" :name "owner"   :value (to-jdbc-varchar-array ["root"])}
   {:resource "1" :name "group"   :value (to-jdbc-varchar-array ["root"])}
   {:resource "2" :name "random"  :value (to-jdbc-varchar-array ["true"])}
   ;; resource 3 deliberately left blank
   {:resource "4" :name "ensure"  :value (to-jdbc-varchar-array ["present"])}
   {:resource "4" :name "content" :value (to-jdbc-varchar-array ["#!/usr/bin/make\nall:\n\techo done\n"])}
   {:resource "5" :name "random"  :value (to-jdbc-varchar-array ["false"])}
   {:resource "6" :name "multi"   :value (to-jdbc-varchar-array ["one" "two" "three"])})
  (sql/insert-records
   :certnames
   {:name "example.local" :api_version 1 :catalog_version "12"}
   {:name "subset.local"  :api_version 1 :catalog_version "14"})
  (doseq [n (range 1 7)]
    (sql/insert-record :certname_resources
                       {:certname "example.local" :resource (str n)}))
  (doseq [n [1 3 5]]
    (sql/insert-record :certname_resources
                       {:certname "subset.local" :resource (str n)}))
  (sql/insert-records
   :resource_tags
   {:resource "4" :name "vivid"})
  ;; structure the results, eh.
  (let [result1 {:hash      "1"
                 :type       "File"
                 :title      "/etc/passwd"
                 :exported   true
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"ensure" ["file"]
                              "owner"  ["root"]
                              "group"  ["root"]}}
        result2 {:hash       "2"
                 :type       "Notify"
                 :title      "hello"
                 :exported   true
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"random" ["true"]}}
        result3 {:hash       "3"
                 :type       "Notify"
                 :title      "no-params"
                 :exported   true
                 :sourcefile nil
                 :sourceline nil}
        result4 {:hash       "4"
                 :type       "File"
                 :title      "/etc/Makefile"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"ensure"  ["present"]
                              "content" ["#!/usr/bin/make\nall:\n\techo done\n"]}}
        result5 {:hash       "5"
                 :type       "Notify"
                 :title      "booyah"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"random" ["false"]}}
        result6 {:hash       "6"
                 :type       "Mval"
                 :title      "multivalue"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"multi" ["one" "two" "three"]}}]
    ;; ...and, finally, ready for testing.
    (testing "queries against SQL data"
      (doseq [[input expect]
              (partition
               2 [ ;; no match
                  ["=" "type" "Banana"]            []
                  ["=" "tag"  "exotic"]            []
                  ["=" ["parameter" "foo"] "bar"]  []
                  ["=" ["node" "certname"] "bar"]  []
                  ;; ...and with an actual match.
                  ["=" "type" "File"]              [result1 result4]
                  ["=" "exported" true]            [result1 result2 result3]
                  ["=" ["parameter" "ensure"] "file"] [result1]
                  ["=" ["node" "certname"] "subset.local"] [result1 result3 result5]
                  ["=" "tag" "vivid"] [result4]
                  ;; multi-value parameter matching!
                  ["=" ["parameter" "multi"] "two"] [result6]
                  ;; and that they don't match *everything*
                  ["=" ["parameter" "multi"] "five"] []
                  ;; testing not operations
                  ["not" ["=" "type" "File"]] [result2 result3 result5 result6]
                  ["not" ["=" "type" "File"] ["=" "type" "Notify"]] [result6]
                  ;; and, or
                  ["and" ["=" "type" "File"] ["=" "title" "/etc/passwd"]] [result1]
                  ["and" ["=" "type" "File"] ["=" "type" "Notify"]] []
                  ["or" ["=" "type" "File"] ["=" "title" "/etc/passwd"]]
                  [result1 result4]
                  ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                  [result1 result2 result3 result4 result5]
                  ;; nesting queries
                  ["and" ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                   ["=" ["node" "certname"] "subset.local"]
                   ["and" ["=" "exported" true]]]
                  [result1 result3]
                  ;; real world query (approximately; real world exported is
                  ;; true, but for convenience around other tests we use
                  ;; false here. :)
                  ["and" ["=" "exported" false]
                   ["not" ["=" ["node" "certname"] "subset.local"]]
                   ["=" "type" "File"]
                   ["=" "tag" "vivid"]]
                  [result4]
                  ])]
        (is (= (s/query-resources *db* (s/query->sql input)) expect)
            (str "  " input " =>\n  " expect))))))


(deftest query-resources-with-extra-FAIL
  (testing "combine terms without arguments"
    (doseq [op ["and" "AND" "or" "OR" "AnD" "Or" "not" "NOT" "NoT"]]
      (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
            (s/query-resources *db* (s/query->sql [op]))))
      (is (thrown-with-msg? IllegalArgumentException (re-pattern (str "(?i)" op))
            (s/query-resources *db* (s/query->sql [op]))))))
  (testing "bad query operators"
    (doseq [in [["if"] ["-"] [{}] [["="]]]]
      (is (thrown-with-msg? IllegalArgumentException #"No method in multimethod"
            (s/query-resources *db* (s/query->sql in))))))
  (testing "wrong number of arguments to ="
    (doseq [in [["="] ["=" "one"] ["=" "three" "three" "three"]]]
      (is (thrown-with-msg? IllegalArgumentException
            (re-pattern (str "operators take two arguments, but we found "
                             (dec (count in))))
            (s/query-resources *db* (s/query->sql in))))))
  (testing "bad types in input"
    (doseq [path (list [] {} [{}] 12 true false 0.12)]
      (doseq [input (list ["=" path "foo"]
                          ["=" [path] "foo"]
                          ["=" ["bar" path] "foo"])]
        (is (thrown-with-msg? IllegalArgumentException
              #"is not a valid query term"
              (s/query-resources *db* (s/query->sql input))))))))



;;;; Test the resource listing handlers.
(def *c-t*   s/resource-list-c-t)

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
   {:resource "1" :name "ensure" :value (to-jdbc-varchar-array ["file"])}
   {:resource "1" :name "owner"  :value (to-jdbc-varchar-array ["root"])}
   {:resource "1" :name "group"  :value (to-jdbc-varchar-array ["root"])}
   {:resource "1" :name "acl"    :value (to-jdbc-varchar-array ["john:rwx" "fred:rwx"])})
  (sql/insert-records
   :certnames
   {:name "one.local" :api_version 1 :catalog_version "12"}
   {:name "two.local" :api_version 1 :catalog_version "14"})
  (doseq [n (range 1 3)]
    (sql/insert-record :certname_resources
                       {:certname "one.local" :resource (str n)}))
  (doseq [n [2]]
    (sql/insert-record :certname_resources
                       {:certname "two.local" :resource (str n)}))
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
                         :parameters {:ensure ["file"]
                                      :owner  ["root"]
                                      :group  ["root"]
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
                   ["=" ["parameter" "acl"]    "fred:rwx"]]]
      (is-response-equal (get-response query)
                         [{:hash       "1"
                           :type       "File"
                           :title      "/etc/passwd"
                           :exported   true
                           :sourcefile nil
                           :sourceline nil
                           :parameters {:ensure ["file"]
                                        :owner  ["root"]
                                        :group  ["root"]
                                        :acl    ["john:rwx" "fred:rwx"]}}])))
  (testing "error handling"
    (let [response (get-response ["="])
          body     (json/parse-string (get response :body "null") true)]
      (is (= (:status response) 400))
      (is (re-find #"operators take two arguments" (:error body))))))
