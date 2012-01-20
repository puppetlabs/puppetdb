(ns com.puppetlabs.cmdb.test.query.resource
  (:require [com.puppetlabs.cmdb.query.resource :as s]
            [com.puppetlabs.cmdb.query :as query]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojureql.core :as clojureql]
            ring.middleware.params)
  (:use clojure.test
        ring.mock.request
        [clojure.contrib.duck-streams :only (read-lines)]
        [com.puppetlabs.cmdb.testutils :only [test-db]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize
                                                initialize-store]]))

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
    (let [query ["=" "title" "whatever"]
          result ["(SELECT DISTINCT resources.hash FROM resources WHERE (resources.title = ?))" "whatever"]]
      (is (= (s/query->sql *db* query) result)
          (str query "=>" result)))
    ;; with a path to the field
    (let [[sql & params] (s/query->sql *db* ["=" ["node" "certname"] "example"])]
      (is (= params ["example"]))
      (is (re-find #"JOIN catalog_resources" sql))
      (is (re-find #"JOIN certname_catalogs" sql))
      (is (re-find #"WHERE \(certname_catalogs.certname = \?\)" sql)))
    (let [[sql & params] (s/query->sql *db* ["=" "tag" "foo"])]
      (is (re-find #"SELECT DISTINCT resources.hash FROM resources" sql))
      (is (re-find #"JOIN resource_tags" sql))
      (is (= params ["foo"]))))
  (testing "order of params in grouping"
    (let [[sql & params] (s/query->sql *db* ["and"
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
       (for [one terms
             two terms]
         (let [[sql1 & param1] (s/query->sql *db* one)
               [sql2 & param2] (s/query->sql *db* two)
               [and-sql & and-params] (s/query->sql *db* ["and" one two])
               [or-sql & or-params] (s/query->sql *db* ["or" one two])]
           (is (= and-sql (format "(SELECT DISTINCT hash FROM %s resources_0 NATURAL JOIN %s resources_1)"
                                  sql1 sql2)))
           (is (= or-sql (format "(%s UNION %s)" sql1 sql2)))
           (is (= and-params (concat param1 param2)))
           (is (= or-params (concat param1 param2)))))))
    (testing "simple {and, or} grouping with many terms"
      (let [terms-  (map (partial s/query->sql *db*) terms)
               queries (map first terms-)
               joins (->> (map #(format "%s resources_%d" %1 %2) queries (range (count queries)))
                          (string/join " NATURAL JOIN "))
               and- (format "(SELECT DISTINCT hash FROM %s)" joins)
               or- (format "(%s)" (string/join " UNION " queries))
               params- (mapcat rest terms-)
               [and-sql & and-params] (s/query->sql *db* (apply vector "and" terms))
               [or-sql & or-params] (s/query->sql *db* (apply vector "or" terms))]
           (is (= and-sql and-))
           (is (= or-sql or-))
           (is (= and-params params-))
           (is (= or-params params-)))))

  (testing "negation"
    (let [[sql & params] (s/query->sql *db* ["not" ["=" "type" "foo"]])]
      (is (= sql (str "(SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN "
                      "((SELECT DISTINCT resources.hash FROM resources WHERE (resources.type = ?))) rhs "
                      "ON (lhs.hash = rhs.hash) WHERE (rhs.hash IS NULL))")))
      (is (= params ["foo"])))
    (let [[sql & params] (s/query->sql *db* ["not" ["=" "type" "foo"]
                                        ["=" "title" "bar"]])]
      (is (= sql (str "(SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN "

                      "("
                      "(SELECT DISTINCT resources.hash FROM resources WHERE (resources.type = ?))"
                      " UNION "
                      "(SELECT DISTINCT resources.hash FROM resources WHERE (resources.title = ?))"
                      ") rhs ON (lhs.hash = rhs.hash) WHERE (rhs.hash IS NULL)"
                      ")")))
      (is (= params ["foo" "bar"]))))

  (testing "real world query"
    (let [[sql & params]
          (s/query->sql *db* ["and"
                         ["not" ["=" ["node" "certname"] "example.local"]]
                         ["=" "exported" true]
                         ["=" ["parameter" "ensure"] "yellow"]])]
      (is (= sql (str "("
                      ;; top level and not certname
                      "SELECT DISTINCT hash FROM "
                      "(SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN ("
                      "(SELECT DISTINCT resources.hash FROM resources "
                      "JOIN catalog_resources ON (resources.hash = catalog_resources.resource) "
                      "JOIN certname_catalogs ON (catalog_resources.catalog = certname_catalogs.catalog) "
                      "WHERE (certname_catalogs.certname = ?))"
                      ") rhs ON (lhs.hash = rhs.hash) WHERE (rhs.hash IS NULL)) resources_0"
                      ;; exported
                      " NATURAL JOIN "
                      "(SELECT DISTINCT resources.hash FROM resources "
                      "WHERE (resources.exported = ?)) resources_1"
                      ;; parameter match
                      " NATURAL JOIN "
                      "(SELECT DISTINCT resources.hash FROM resources JOIN resource_params "
                      "ON (resource_params.resource = resources.hash) "
                      "WHERE ((resource_params.name = ?) AND "
                      "(resource_params.value = ?))"
                      ") resources_2"
                      ")")))
      (is (= params ["example.local" true "ensure" (db-serialize "yellow")])))))


;; now, for some end-to-end testing with a database...
(deftest query-resources
  (sql/insert-records
   :resources
   {:hash "1" :type "File"   :title "/etc/passwd"   :exported true}
   {:hash "2" :type "Notify" :title "hello"         :exported true}
   {:hash "3" :type "Notify" :title "no-params"     :exported true}
   {:hash "4" :type "File"   :title "/etc/Makefile" :exported false}
   {:hash "5" :type "Notify" :title "booyah"        :exported false}
   {:hash "6" :type "Mval"   :title "multivalue"    :exported false}
   {:hash "7" :type "Hval"   :title "hashvalue"     :exported false})
  (sql/insert-records
   :resource_params
   {:resource "1" :name "ensure"  :value (db-serialize "file")}
   {:resource "1" :name "owner"   :value (db-serialize "root")}
   {:resource "1" :name "group"   :value (db-serialize "root")}
   {:resource "2" :name "random"  :value (db-serialize "true")}
   ;; resource 3 deliberately left blank
   {:resource "4" :name "ensure"  :value (db-serialize "present")}
   {:resource "4" :name "content" :value (db-serialize "#!/usr/bin/make\nall:\n\techo done\n")}
   {:resource "5" :name "random"  :value (db-serialize "false")}
   {:resource "6" :name "multi"   :value (db-serialize ["one" "two" "three"])}
   {:resource "7" :name "hash"    :value (db-serialize {"foo" 5 "bar" 10})})
  (sql/insert-records
   :certnames
   {:name "example.local"}
   {:name "subset.local"})
  (sql/insert-records
    :catalogs
    {:hash "foo" :api_version 1 :catalog_version "12"}
    {:hash "bar" :api_version 1 :catalog_version "14"})
  (sql/insert-records
    :certname_catalogs
    {:certname "example.local" :catalog "foo"}
    {:certname "subset.local" :catalog "bar"})
  (apply sql/insert-records :catalog_resources
         (for [n (range 1 8)]
           {:catalog "foo" :resource (str n)}))
  (apply sql/insert-records :catalog_resources
         (for [n [1 3 5]]
           {:catalog "bar" :resource (str n)}))
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
                 :parameters {"ensure" "file"
                              "owner"  "root"
                              "group"  "root"}}
        result2 {:hash       "2"
                 :type       "Notify"
                 :title      "hello"
                 :exported   true
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"random" "true"}}
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
                 :parameters {"ensure"  "present"
                              "content" "#!/usr/bin/make\nall:\n\techo done\n"}}
        result5 {:hash       "5"
                 :type       "Notify"
                 :title      "booyah"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"random" "false"}}
        result6 {:hash       "6"
                 :type       "Mval"
                 :title      "multivalue"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"multi" ["one" "two" "three"]}}
        result7 {:hash       "7"
                 :type       "Hval"
                 :title      "hashvalue"
                 :exported   false
                 :sourcefile nil
                 :sourceline nil
                 :parameters {"hash" {"foo" 5 "bar" 10}}}]
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
                  ;; array parameter matching
                  ["=" ["parameter" "multi"] ["one" "two" "three"]] [result6]
                  ["=" ["parameter" "multi"] ["one" "three" "two"]] []
                  ["=" ["parameter" "multi"] "three"] []
                  ;; hash parameter matching
                  ["=" ["parameter" "hash"] {"foo" 5 "bar" 10}] [result7]
                  ["=" ["parameter" "hash"] {"bar" 10 "foo" 5}] [result7]
                  ["=" ["parameter" "hash"] {"bar" 10}] []
                  ;; testing not operations
                  ["not" ["=" "type" "File"]] [result2 result3 result5 result6 result7]
                  ["not" ["=" "type" "File"] ["=" "type" "Notify"]] [result6 result7]
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
        (is (= (s/query-resources *db* (s/query->sql *db* input)) expect)
            (str "  " input " =>\n  " expect))))))


(deftest query-resources-with-extra-FAIL
  (testing "combine terms without arguments"
    (doseq [op ["and" "AND" "or" "OR" "AnD" "Or" "not" "NOT" "NoT"]]
      (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
            (s/query-resources *db* (s/query->sql *db* [op]))))
      (is (thrown-with-msg? IllegalArgumentException (re-pattern (str "(?i)" op))
            (s/query-resources *db* (s/query->sql *db* [op]))))))
  (testing "bad query operators"
    (doseq [in [["if"] ["-"] [{}] [["="]]]]
      (is (thrown-with-msg? IllegalArgumentException #"No method in multimethod"
            (s/query-resources *db* (s/query->sql *db* in))))))
  (testing "wrong number of arguments to ="
    (doseq [in [["="] ["=" "one"] ["=" "three" "three" "three"]]]
      (is (thrown-with-msg? IllegalArgumentException
            (re-pattern (str "operators take two arguments, but we found "
                             (dec (count in))))
            (s/query-resources *db* (s/query->sql *db* in))))))
  (testing "bad types in input"
    (doseq [path (list [] {} [{}] 12 true false 0.12)]
      (doseq [input (list ["=" path "foo"]
                          ["=" [path] "foo"]
                          ["=" ["bar" path] "foo"])]
        (is (thrown-with-msg? IllegalArgumentException
              #"is not a valid query term"
              (s/query-resources *db* (s/query->sql *db* input))))))))



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
