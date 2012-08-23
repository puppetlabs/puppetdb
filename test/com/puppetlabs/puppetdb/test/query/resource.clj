(ns com.puppetlabs.puppetdb.test.query.resource
  (:require [com.puppetlabs.puppetdb.query.resource :as s]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]
        [com.puppetlabs.utils :only [mapkeys mapvals]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize to-jdbc-varchar-array]]))

(use-fixtures :each with-test-db)

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
    {:certname "example.local" :catalog "foo" :timestamp (to-timestamp (now))}
    {:certname "subset.local" :catalog "bar" :timestamp (to-timestamp (now)) })
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
                  ;; case-insensitive tags
                  ["=" "tag" "VIVID"] [foo4]
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


(deftest query-resources-multiple-catalogs
  (testing "should only query against the most recent catalog"
    (let [add-tag-to-resources (fn [resources tag]
                                 (mapvals #(update-in % [:tags] conj tag) resources))
          one-day  (* 24 60 60 1000)
          tomorrow (to-timestamp (+ (System/currentTimeMillis) one-day))
          catalog1 (-> (:basic catalogs)
                     (update-in [:resources] add-tag-to-resources "from-catalog1"))
          catalog2 (-> (:basic catalogs)
                     (update-in [:resources] add-tag-to-resources "from-catalog2"))]
      (scf-store/add-certname! (:certname catalog1))
      (scf-store/store-catalog-for-certname! catalog1 (to-timestamp (now)))
      (scf-store/store-catalog-for-certname! catalog2 tomorrow))

    (let [result (s/query-resources (s/query->sql ["=" "type" "File"]))
          munged-result (for [resource result]
                          (-> resource
                            (select-keys [:type :title :tags :parameters])
                            (update-in [:tags] sort)))
          expected [{:type       "File"
                     :title      "/etc/foobar"
                     :tags       ["class" "file" "foobar" "from-catalog2"]
                     :parameters {"ensure" "directory"
                                  "group"  "root"
                                  "user"   "root"}}
                    {:type       "File"
                     :title      "/etc/foobar/baz"
                     :tags       ["class" "file" "foobar" "from-catalog2"]
                     :parameters {"ensure"  "directory"
                                  "group"   "root"
                                  "user"    "root"
                                  "require" "File[/etc/foobar]"}}]]
      (is (= munged-result expected)))))
