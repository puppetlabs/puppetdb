(ns com.puppetlabs.puppetdb.test.query.resources
  (:require [com.puppetlabs.puppetdb.query.resources :as s]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.jdbc]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize to-jdbc-varchar-array]]))

(use-fixtures :each with-test-db)

(defn- raw-query-resources
  [query paging-options]
  (->> (s/v3-query->sql query paging-options)
       (s/limited-query-resources 0 paging-options)))

(defn query-resources
  ([query]
    (:result (s/query-resources query)))
  ([query paging-options]
    (:result (raw-query-resources query paging-options))))

(deftest test-query-resources
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
    {:catalog "foo" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array []) :file "a" :line 1}
    {:catalog "foo" :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array []) :file "a" :line 2}
    {:catalog "foo" :resource "3" :type "Notify" :title "no-params" :exported true :tags (to-jdbc-varchar-array []) :file "c" :line 1}
    {:catalog "foo" :resource "4" :type "File" :title "/etc/Makefile" :exported false :tags (to-jdbc-varchar-array ["vivid"]) :file "d" :line 1}
    {:catalog "foo" :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 2}
    {:catalog "foo" :resource "6" :type "Mval" :title "multivalue" :exported false :tags (to-jdbc-varchar-array []) :file "e" :line 1}
    {:catalog "foo" :resource "7" :type "Hval" :title "hashvalue" :exported false :tags (to-jdbc-varchar-array []) :file "f" :line 1}
    {:catalog "foo" :resource "8" :type "Notify" :title "semver" :exported false :tags (to-jdbc-varchar-array ["1.3.7+build.11.e0f985a"]) :file "f" :line 1}
    {:catalog "bar" :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array []) :file "b" :line 1}
    {:catalog "bar" :resource "3" :type "Notify" :title "no-params" :exported false :tags (to-jdbc-varchar-array []) :file "c" :line 2}
    {:catalog "bar" :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 3})
  (let [foo1 {:certname   "example.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       []
              :exported   true
              :file "a"
              :line 1
              :parameters {"ensure" "file"
                           "owner"  "root"
                           "group"  "root"}}
        bar1 {:certname   "subset.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       []
              :exported   true
              :file "b"
              :line 1
              :parameters {"ensure" "file"
                           "owner"  "root"
                           "group"  "root"}}
        foo2 {:certname   "example.local"
              :resource   "2"
              :type       "Notify"
              :title      "hello"
              :tags       []
              :exported   true
              :file "a"
              :line 2
              :parameters {"random" "true"}}
        foo3 {:certname   "example.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   true
              :file "c"
              :line 1
              :parameters {}}
        bar3 {:certname   "subset.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   false
              :file "c"
              :line 2
              :parameters {}}
        foo4 {:certname   "example.local"
              :resource   "4"
              :type       "File"
              :title      "/etc/Makefile"
              :tags       ["vivid"]
              :exported   false
              :file "d"
              :line 1
              :parameters {"ensure"  "present"
                           "content" "#!/usr/bin/make\nall:\n\techo done\n"}}
        foo5 {:certname   "example.local"
              :resource   "5"
              :type       "Notify"
              :title      "booyah"
              :tags       []
              :exported   false
              :file "d"
              :line 2
              :parameters {"random" "false"}}
        bar5 {:certname   "subset.local"
              :resource   "5"
              :type       "Notify"
              :title      "booyah"
              :tags       []
              :exported   false
              :file "d"
              :line 3
              :parameters {"random" "false"}}
        foo6 {:certname   "example.local"
              :resource   "6"
              :type       "Mval"
              :title      "multivalue"
              :tags       []
              :exported   false
              :file "e"
              :line 1
              :parameters {"multi" ["one" "two" "three"]}}
        foo7 {:certname   "example.local"
              :resource   "7"
              :type       "Hval"
              :title      "hashvalue"
              :tags       []
              :exported   false
              :file "f"
              :line 1
              :parameters {"hash" {"foo" 5 "bar" 10}}}
        foo8 {:certname   "example.local"
              :resource   "8"
              :type       "Notify"
              :title      "semver"
              :tags       ["1.3.7+build.11.e0f985a"]
              :exported   false
              :file "f"
              :line 1
              :parameters {}}
        ]
    ;; ...and, finally, ready for testing.
    (testing "queries against SQL data"
      (doseq [[input expect]
              (partition
               2 [ ;; no match
                  ["=" "type" "Banana"]            []
                  ["=" "tag"  "exotic"]            []
                  ["=" ["parameter" "foo"] "bar"]  []
                  ["=" "certname" "bar"]  []
                  ;; ...and with an actual match.
                  ["=" "type" "File"]              [foo1 bar1 foo4]
                  ["=" "exported" true]            [foo1 bar1 foo2 foo3]
                  ["=" ["parameter" "ensure"] "file"] [foo1 bar1]
                  ["=" "certname" "subset.local"] [bar1 bar3 bar5]
                  ["=" "tag" "vivid"] [foo4]
                  ;; case-insensitive tags
                  ["=" "tag" "VIVID"] [foo4]
                  ;; array parameter matching
                  ["=" ["parameter" "multi"] ["one" "two" "three"]] [foo6]
                  ["=" ["parameter" "multi"] ["one" "three" "two"]] []
                  ["=" ["parameter" "multi"] "three"] []
                  ;; metadata
                  ["=" "file" "c"] [foo3 bar3]
                  ["=" "line" 3] [bar5]
                  ["and" ["=" "file" "c"] ["=" "line" 1]] [foo3]
                  ;; hash parameter matching
                  ["=" ["parameter" "hash"] {"foo" 5 "bar" 10}] [foo7]
                  ["=" ["parameter" "hash"] {"bar" 10 "foo" 5}] [foo7]
                  ["=" ["parameter" "hash"] {"bar" 10}] []
                  ;; testing not operations
                  ["not" ["=" "type" "File"]] [foo2 foo3 bar3 foo5 bar5 foo6 foo7 foo8]
                  ["not" ["=" "type" "Notify"]] [foo1 bar1 foo4 foo6 foo7]
                  ;; and, or
                  ["and" ["=" "type" "File"] ["=" "title" "/etc/passwd"]] [foo1 bar1]
                  ["and" ["=" "type" "File"] ["=" "type" "Notify"]] []
                  ["or" ["=" "type" "File"] ["=" "title" "/etc/passwd"]]
                  [foo1 bar1 foo4]
                  ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                  [foo1 bar1 foo2 foo3 bar3 foo4 foo5 bar5 foo8]
                  ;; regexp
                  ["~" "certname" "ubs.*ca.$"] [bar1 bar3 bar5]
                  ["~" "title" "^[bB]o..a[Hh]$"] [foo5 bar5]
                  ["~" "tag" "^[vV]..id$"] [foo4]
                  ["or"
                   ["~" "tag" "^[vV]..id$"]
                   ["~" "tag" "^..vi.$"]]
                  [foo4]
                  ;; heinous regular expression to detect semvers
                  ["~" "tag" "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"]
                  [foo8]
                  ;; nesting queries
                  ["and" ["or" ["=" "type" "File"] ["=" "type" "Notify"]]
                   ["=" "certname" "subset.local"]
                   ["and" ["=" "exported" true]]]
                  [bar1]
                  ;; real world query (approximately; real world exported is
                  ;; true, but for convenience around other tests we use
                  ;; false here. :)
                  ["and" ["=" "exported" false]
                   ["not" ["=" "certname" "subset.local"]]
                   ["=" "type" "File"]
                   ["=" "tag" "vivid"]]
                  [foo4]
                  ])]
        (is (= (set (query-resources (s/v3-query->sql input))) (set expect))
            (str "  " input " =>\n  " expect))))

    (testing "v2 vs v3"
      (testing "file/line in v2"
        (doseq [param ["file" "line"]]
          (is (thrown-with-msg? IllegalArgumentException #"is not a queryable object"
                (query-resources (s/v2-query->sql ["=" param "foo"])))))

        (doseq [[input expect]
                (partition
                  2 [
                      ["=" "sourcefile" "c"] [foo3 bar3]
                      ["=" "sourceline" 3] [bar5]
                      ])]
          (is (= (set (query-resources (s/v2-query->sql input))) (set expect))
            (str "  " input " =>\n  " expect)))))))


(deftest query-resources-with-extra-FAIL
  (testing "combine terms without arguments"
    (doseq [op ["and" "AND" "or" "OR" "AnD" "Or"]]
      (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
            (query-resources (s/v3-query->sql [op]))))
      (is (thrown-with-msg? IllegalArgumentException (re-pattern (str "(?i)" op))
            (query-resources (s/v3-query->sql [op]))))))

  (testing "'not' term without arguments in v1"
    (doseq [op ["not" "NOT" "NoT"]]
      (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
            (query-resources (s/v1-query->sql [op]))))))

  (testing "'not' term without arguments in v2"
    (doseq [op ["not" "NOT" "NoT"]]
      (is (thrown-with-msg? IllegalArgumentException #"'not' takes exactly one argument, but 0 were supplied"
            (query-resources (s/v3-query->sql [op]))))))

  (testing "bad query operators"
    (doseq [in [["if"] ["-"] [{}] [["="]]]]
      (is (thrown-with-msg? IllegalArgumentException #"query operator .* is unknown"
            (query-resources (s/v3-query->sql in))))))

  (testing "wrong number of arguments to ="
    (doseq [in [["="] ["=" "one"] ["=" "three" "three" "three"]]]
      (is (thrown-with-msg? IllegalArgumentException
            (re-pattern (format "= requires exactly two arguments, but %d were supplied"
                             (dec (count in))))
            (query-resources (s/v3-query->sql in))))))

  (testing "invalid columns"
    (is (thrown-with-msg? IllegalArgumentException #"is not a queryable object"
          (query-resources (s/v3-query->sql ["=" "foobar" "anything"])))))

  (testing "bad types in input"
    (doseq [path (list [] {} [{}] 12 true false 0.12)]
      (doseq [input (list ["=" path "foo"]
                          ["=" [path] "foo"]
                          ["=" ["bar" path] "foo"])]
        (is (thrown-with-msg? IllegalArgumentException
              #"is not a queryable object"
              (query-resources (s/v3-query->sql input))))))))

(deftest paging-results
  (sql/insert-records
   :resource_params
   {:resource "1" :name "ensure"  :value (db-serialize "file")}
   {:resource "1" :name "owner"   :value (db-serialize "root")}
   {:resource "4" :name "ensure"  :value (db-serialize "present")}
   {:resource "1" :name "group"   :value (db-serialize "root")}
   {:resource "3" :name "hash"    :value (db-serialize {"foo" 5 "bar" 10})}
   {:resource "2" :name "random"  :value (db-serialize "true")}
   {:resource "3" :name "multi"   :value (db-serialize ["one" "two" "three"])}
   {:resource "4" :name "content" :value (db-serialize "contents")}
   {:resource "2" :name "enabled" :value (db-serialize "false")})

  (sql/insert-records :certnames
    {:name "foo.local"})
  (sql/insert-records :catalogs
    {:hash "foo" :api_version 1 :catalog_version "12"})
  (sql/insert-records :certname_catalogs
    {:certname "foo.local" :catalog "foo"})
  (sql/insert-records :catalog_resources
    {:catalog "foo" :resource "1" :type "File" :title "alpha"   :exported true  :tags (to-jdbc-varchar-array []) :file "a" :line 1}
    {:catalog "foo" :resource "2" :type "File" :title "beta"    :exported true  :tags (to-jdbc-varchar-array []) :file "a" :line 4}
    {:catalog "foo" :resource "3" :type "File" :title "charlie" :exported true  :tags (to-jdbc-varchar-array []) :file "c" :line 2}
    {:catalog "foo" :resource "4" :type "File" :title "delta"   :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 3})

  (let [r1 {:certname "foo.local" :resource "1" :type "File" :title "alpha"   :tags [] :exported true  :file "a" :line 1 :parameters {"ensure" "file" "group" "root" "owner" "root"}}
        r2 {:certname "foo.local" :resource "2" :type "File" :title "beta"    :tags [] :exported true  :file "a" :line 4 :parameters {"enabled" "false" "random" "true"}}
        r3 {:certname "foo.local" :resource "3" :type "File" :title "charlie" :tags [] :exported true  :file "c" :line 2 :parameters {"hash" {"bar" 10 "foo" 5} "multi" '("one" "two" "three")}}
        r4 {:certname "foo.local" :resource "4" :type "File" :title "delta"   :tags [] :exported false :file "d" :line 3 :parameters {"content" "contents" "ensure" "present"}}]

    (testing "include total results count"
      (let [expected 4
            actual   (:count (raw-query-resources ["=" ["node" "active"] true] {:count? true}))]
        (is (= actual expected))))

    (testing "limit results"
    (doseq [[limit expected] [[0 0] [2 2] [100 4]]]
      (let [results (query-resources ["=" ["node" "active"] true] {:limit limit})
            actual  (count results)]
        (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
              (query-resources [] {:order-by [{:field "invalid-field"}]}))))

      (testing "defaults to ascending"
        (let [expected [r1 r3 r4 r2]
              actual   (query-resources ["=" ["node" "active"] true] {:order-by [{:field "line"}]})]
          (is (= actual expected))))

      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC"  [r1 r2 r3 r4]]
                                  ["DESC" [r4 r3 r2 r1]]]]
          (testing order
            (let [actual (query-resources ["=" ["node" "active"] true] {:order-by [{:field "title" :order order}]})]
              (is (= actual expected))))))

      (testing "numerical fields"
        (doseq [[order expected] [["ASC"  [r1 r3 r4 r2]]
                                  ["DESC" [r2 r4 r3 r1]]]]
          (testing order
            (let [actual (query-resources ["=" ["node" "active"] true] {:order-by [{:field "line" :order order}]})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[file-order line-order] expected] [[["ASC" "DESC"]  [r2 r1 r3 r4]]
                                                    [["ASC" "ASC"]   [r1 r2 r3 r4]]
                                                    [["DESC" "ASC"]  [r4 r3 r1 r2]]
                                                    [["DESC" "DESC"] [r4 r3 r2 r1]]]]
          (testing (format "file %s line %s" file-order line-order)
            (let [actual (query-resources ["=" ["node" "active"] true] {:order-by [{:field "file" :order file-order}
                                                                                   {:field "line" :order line-order}]})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [r1 r2 r3 r4]]
                                                   [1 [r2 r3 r4]]
                                                   [2 [r3 r4]]
                                                   [3 [r4]]
                                                   [4 []]]]
                                          ["DESC" [[0 [r4 r3 r2 r1]]
                                                   [1 [r3 r2 r1]]
                                                   [2 [r2 r1]]
                                                   [3 [r1]]
                                                   [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-resources ["=" ["node" "active"] true] {:order-by [{:field "title" :order order}] :offset offset})]
              (is (= actual expected)))))))))
