(ns com.puppetlabs.cmdb.test.query.resource
  (:require [com.puppetlabs.cmdb.query.resource :as s]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.jdbc :only (query-to-vec with-transacted-connection)]
        [com.puppetlabs.cmdb.testutils :only [with-test-db]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize to-jdbc-varchar-array]]
        [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def ^:dynamic *db* nil)

(use-fixtures :each (fn [f]
                      (with-test-db *db*
                        (with-transacted-connection *db*
                          (migrate!)
                          (f)))))

(deftest query->sql
  (testing "comparisons"
    ;; simple, local attributes
    (let [query ["=" "title" "whatever"]
          result [(str "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources "
                       "WHERE (catalog_resources.title = ?))")
                  "whatever"]]
      (is (= (s/query->sql query) result)
          (str query "=>" result)))
    ;; with a path to the field
    (let [[sql & params] (s/query->sql ["=" ["node" "name"] "example"])]
      (is (= params ["example"]))
      (is (re-find #"SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM certname_catalogs JOIN catalog_resources" sql))
      (is (re-find #"\(certname_catalogs.certname = \?\)" sql)))
    (let [[sql & params] (s/query->sql ["=" "tag" "foo"])]
      (is (re-find #"SELECT DISTINCT catalog,resource FROM catalog_resources" sql))
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
               ["=" ["node" "name"] "four"]
               ["=" ["parameter" "ensure"] "five"]
               ["=" ["parameter" "banana"] "yumm"]]]
    (testing "simple {and, or} grouping"
      (doall
       (for [one terms
             two terms]
         (let [[sql1 & param1] (s/query->sql one)
               [sql2 & param2] (s/query->sql two)
               [and-sql & and-params] (s/query->sql ["and" one two])
               [or-sql & or-params] (s/query->sql ["or" one two])]
           (is (= and-sql (format "(SELECT DISTINCT catalog,resource FROM %s resources_0 NATURAL JOIN %s resources_1)"
                                  sql1 sql2)))
           (is (= or-sql (format "(%s UNION %s)" sql1 sql2)))
           (is (= and-params (concat param1 param2)))
           (is (= or-params (concat param1 param2)))))))
    (testing "simple {and, or} grouping with many terms"
      (let [terms-  (map s/query->sql terms)
               queries (map first terms-)
               joins (->> (map #(format "%s resources_%d" %1 %2) queries (range (count queries)))
                          (string/join " NATURAL JOIN "))
               and- (format "(SELECT DISTINCT catalog,resource FROM %s)" joins)
               or- (format "(%s)" (string/join " UNION " queries))
               params- (mapcat rest terms-)
               [and-sql & and-params] (s/query->sql (apply vector "and" terms))
               [or-sql & or-params] (s/query->sql (apply vector "or" terms))]
           (is (= and-sql and-))
           (is (= or-sql or-))
           (is (= and-params params-))
           (is (= or-params params-)))))

  (testing "negation"
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]])]
      (is (= sql (str "(SELECT DISTINCT lhs.catalog,lhs.resource FROM catalog_resources lhs LEFT OUTER JOIN "
                      "((SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources "
                      "WHERE (catalog_resources.type = ?))) rhs "
                      "ON lhs.catalog = rhs.catalog AND lhs.resource = rhs.resource WHERE (rhs.resource IS NULL))")))
      (is (= params ["foo"])))
    (let [[sql & params] (s/query->sql ["not" ["=" "type" "foo"]
                                        ["=" "title" "bar"]])]
      (is (= sql (str "(SELECT DISTINCT lhs.catalog,lhs.resource FROM catalog_resources lhs LEFT OUTER JOIN "
                      "("
                      "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources WHERE (catalog_resources.type = ?))"
                      " UNION "
                      "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources WHERE (catalog_resources.title = ?))"
                      ") rhs ON lhs.catalog = rhs.catalog AND lhs.resource = rhs.resource WHERE (rhs.resource IS NULL)"
                      ")")))
      (is (= params ["foo" "bar"]))))

  (testing "real world query"
    (let [[sql & params]
          (s/query->sql ["and"
                         ["not" ["=" ["node" "name"] "example.local"]]
                         ["=" "exported" true]
                         ["=" ["parameter" "ensure"] "yellow"]])]
      (is (= sql (str "("
                      ;; top level and not certname
                      "SELECT DISTINCT catalog,resource FROM "
                      "(SELECT DISTINCT lhs.catalog,lhs.resource FROM catalog_resources lhs LEFT OUTER JOIN ("
                      "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM certname_catalogs "
                      "JOIN catalog_resources USING(catalog) "
                      "WHERE (certname_catalogs.certname = ?))) "
                      "rhs ON lhs.catalog = rhs.catalog AND lhs.resource = rhs.resource WHERE (rhs.resource IS NULL)) resources_0"
                      ;; exported
                      " NATURAL JOIN "
                      "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM catalog_resources "
                      "WHERE (catalog_resources.exported = ?)) resources_1"
                      ;; parameter match
                      " NATURAL JOIN "
                      "(SELECT DISTINCT catalog_resources.catalog,catalog_resources.resource FROM resource_params "
                      "JOIN catalog_resources USING(resource) "
                      "WHERE ((resource_params.name = ?) AND (resource_params.value = ?))) resources_2"
                      ")")))
      (is (= params ["example.local" true "ensure" (db-serialize "yellow")])))))


;; now, for some end-to-end testing with a database...
(deftest query-resources
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
  (sql/insert-records :catalog_resources
    {:catalog "foo" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array [])}
    {:catalog "foo" :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array [])}
    {:catalog "foo" :resource "3" :type "Notify" :title "no-params" :exported true :tags (to-jdbc-varchar-array [])}
    {:catalog "foo" :resource "4" :type "File" :title "/etc/Makefile" :exported false :tags (to-jdbc-varchar-array ["vivid"])}
    {:catalog "foo" :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array [])}
    {:catalog "foo" :resource "6" :type "Mval" :title "multivalue" :exported false :tags (to-jdbc-varchar-array [])}
    {:catalog "foo" :resource "7" :type "Hval" :title "hashvalue" :exported false :tags (to-jdbc-varchar-array [])}
    {:catalog "bar" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array [])}
    {:catalog "bar" :resource "3" :type "Notify" :title "no-params" :exported false :tags (to-jdbc-varchar-array [])}
    {:catalog "bar" :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array [])})
  ;; structure the results, eh.
  (let [foo1 {:certname   "example.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       []
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {"ensure" "file"
                           "owner"  "root"
                           "group"  "root"}}
        bar1 {:certname   "subset.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       []
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {"ensure" "file"
                           "owner"  "root"
                           "group"  "root"}}
        foo2 {:certname   "example.local"
              :resource   "2"
              :type       "Notify"
              :title      "hello"
              :tags       []
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {"random" "true"}}
        foo3 {:certname   "example.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   true
              :sourcefile nil
              :sourceline nil
              :parameters {}}
        bar3 {:certname   "subset.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   false
              :sourcefile nil
              :sourceline nil
              :parameters {}}
        foo4 {:certname   "example.local"
              :resource   "4"
              :type       "File"
              :title      "/etc/Makefile"
              :tags       ["vivid"]
              :exported   false
              :sourcefile nil
              :sourceline nil
              :parameters {"ensure"  "present"
                           "content" "#!/usr/bin/make\nall:\n\techo done\n"}}
        foo5 {:certname   "example.local"
              :resource   "5"
              :type       "Notify"
              :title      "booyah"
              :tags       []
              :exported   false
              :sourcefile nil
              :sourceline nil
              :parameters {"random" "false"}}
        bar5 {:certname   "subset.local"
              :resource   "5"
              :type       "Notify"
              :title      "booyah"
              :tags       []
              :exported   false
              :sourcefile nil
              :sourceline nil
              :parameters {"random" "false"}}
        foo6 {:certname   "example.local"
              :resource   "6"
              :type       "Mval"
              :title      "multivalue"
              :tags       []
              :exported   false
              :sourcefile nil
              :sourceline nil
              :parameters {"multi" ["one" "two" "three"]}}
        foo7 {:certname   "example.local"
              :resource   "7"
              :type       "Hval"
              :title      "hashvalue"
              :tags       []
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
                  ["=" ["node" "name"] "bar"]  []
                  ;; ...and with an actual match.
                  ["=" "type" "File"]              [foo1 bar1 foo4]
                  ["=" "exported" true]            [foo1 bar1 foo2 foo3]
                  ["=" ["parameter" "ensure"] "file"] [foo1 bar1]
                  ["=" ["node" "name"] "subset.local"] [bar1 bar3 bar5]
                  ["=" "tag" "vivid"] [foo4]
                  ;; array parameter matching
                  ["=" ["parameter" "multi"] ["one" "two" "three"]] [foo6]
                  ["=" ["parameter" "multi"] ["one" "three" "two"]] []
                  ["=" ["parameter" "multi"] "three"] []
                  ;; hash parameter matching
                  ["=" ["parameter" "hash"] {"foo" 5 "bar" 10}] [foo7]
                  ["=" ["parameter" "hash"] {"bar" 10 "foo" 5}] [foo7]
                  ["=" ["parameter" "hash"] {"bar" 10}] []
                  ;; testing not operations
                  ["not" ["=" "type" "File"]] [foo2 foo3 bar3 foo5 bar5 foo6 foo7]
                  ["not" ["=" "type" "File"] ["=" "type" "Notify"]] [foo6 foo7]
                  ;; and, or
                  ["and" ["=" "type" "File"] ["=" "title" "/etc/passwd"]] [foo1 bar1]
                  ["and" ["=" "type" "File"] ["=" "type" "Notify"]] []
                  ["or" ["=" "type" "File"] ["=" "title" "/etc/passwd"]]
                  [foo1 bar1 foo4]
                  ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                  [foo1 bar1 foo2 foo3 bar3 foo4 foo5 bar5]
                  ;; nesting queries
                  ["and" ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                   ["=" ["node" "name"] "subset.local"]
                   ["and" ["=" "exported" true]]]
                  [bar1]
                  ;; real world query (approximately; real world exported is
                  ;; true, but for convenience around other tests we use
                  ;; false here. :)
                  ["and" ["=" "exported" false]
                   ["not" ["=" ["node" "name"] "subset.local"]]
                   ["=" "type" "File"]
                   ["=" "tag" "vivid"]]
                  [foo4]
                  ])]
        (is (= (set (s/query-resources (s/query->sql input))) (set expect))
            (str "  " input " =>\n  " expect))))))


(deftest query-resources-with-extra-FAIL
  (testing "combine terms without arguments"
    (doseq [op ["and" "AND" "or" "OR" "AnD" "Or" "not" "NOT" "NoT"]]
      (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
            (s/query-resources (s/query->sql [op]))))
      (is (thrown-with-msg? IllegalArgumentException (re-pattern (str "(?i)" op))
            (s/query-resources (s/query->sql [op]))))))
  (testing "bad query operators"
    (doseq [in [["if"] ["-"] [{}] [["="]]]]
      (is (thrown-with-msg? IllegalArgumentException #"No method in multimethod"
            (s/query-resources (s/query->sql in))))))
  (testing "wrong number of arguments to ="
    (doseq [in [["="] ["=" "one"] ["=" "three" "three" "three"]]]
      (is (thrown-with-msg? IllegalArgumentException
            (re-pattern (str "= requires exactly two arguments, but we found "
                             (dec (count in))))
            (s/query-resources (s/query->sql in))))))
  (testing "bad types in input"
    (doseq [path (list [] {} [{}] 12 true false 0.12)]
      (doseq [input (list ["=" path "foo"]
                          ["=" [path] "foo"]
                          ["=" ["bar" path] "foo"])]
        (is (thrown-with-msg? IllegalArgumentException
              #"is not a valid query term"
              (s/query-resources (s/query->sql input))))))))

