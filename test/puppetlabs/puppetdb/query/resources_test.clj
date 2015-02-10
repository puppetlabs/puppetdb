(ns puppetlabs.puppetdb.query.resources-test
  (:require [puppetlabs.puppetdb.query.resources :as s]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.scf.storage :refer [ensure-environment]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.scf.storage-utils :refer [db-serialize to-jdbc-varchar-array]]))

(use-fixtures :each with-test-db)

(defn- raw-query-resources
  [version query paging-options]
  (->> (s/query->sql version query paging-options)
       (s/query-resources version)))

(defn query-resources
  [version query]
  (:result (s/query-resources version query)))

(deftest test-query-resources
  (sql/insert-records
   :resource_params_cache
   {:resource "1" :parameters (db-serialize {"ensure" "file"
                                             "owner"  "root"
                                             "group"  "root"})}
   {:resource "2" :parameters (db-serialize {"random" "true"})}
   {:resource "3" :parameters nil}
   {:resource "4" :parameters (db-serialize {"ensure"  "present"
                                             "content" "#!/usr/bin/make\nall:\n\techo done\n"})}
   {:resource "5" :parameters (db-serialize {"random" "false"})}
   {:resource "6" :parameters (db-serialize {"multi" ["one" "two" "three"]})}
   {:resource "7" :parameters (db-serialize {"hash" {"foo" 5 "bar" 10}})}
   {:resource "8" :parameters nil})

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
   {:id 1 :hash "foo" :api_version 1 :catalog_version "12" :certname "example.local" :environment_id (ensure-environment "DEV")}
   {:id 2 :hash "bar" :api_version 1 :catalog_version "14" :certname "subset.local" :environment_id nil})

  (sql/insert-records :catalog_resources
                      {:catalog_id 1 :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array []) :file "a" :line 1}
                      {:catalog_id 1 :resource "2" :type "Notify" :title "hello" :exported true :tags (to-jdbc-varchar-array []) :file "a" :line 2}
                      {:catalog_id 1 :resource "3" :type "Notify" :title "no-params" :exported true :tags (to-jdbc-varchar-array []) :file "c" :line 1}
                      {:catalog_id 1 :resource "4" :type "File" :title "/etc/Makefile" :exported false :tags (to-jdbc-varchar-array ["vivid"]) :file "d" :line 1}
                      {:catalog_id 1 :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 2}
                      {:catalog_id 1 :resource "6" :type "Mval" :title "multivalue" :exported false :tags (to-jdbc-varchar-array []) :file "e" :line 1}
                      {:catalog_id 1 :resource "7" :type "Hval" :title "hashvalue" :exported false :tags (to-jdbc-varchar-array []) :file "f" :line 1}
                      {:catalog_id 1 :resource "8" :type "Notify" :title "semver" :exported false :tags (to-jdbc-varchar-array ["1.3.7+build.11.e0f985a"]) :file "f" :line 1}
                      {:catalog_id 2 :resource "1" :type "File" :title "/etc/passwd" :exported true :tags (to-jdbc-varchar-array []) :file "b" :line 1}
                      {:catalog_id 2 :resource "3" :type "Notify" :title "no-params" :exported false :tags (to-jdbc-varchar-array []) :file "c" :line 2}
                      {:catalog_id 2 :resource "5" :type "Notify" :title "booyah" :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 3})
  (let [foo1 {:certname   "example.local"
              :resource   "1"
              :type       "File"
              :title      "/etc/passwd"
              :tags       []
              :exported   true
              :file "a"
              :line 1
              :environment "DEV"
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
              :environment nil
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
              :environment "DEV"
              :parameters {"random" "true"}}
        foo3 {:certname   "example.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   true
              :file "c"
              :line 1
              :environment "DEV"
              :parameters {}}
        bar3 {:certname   "subset.local"
              :resource   "3"
              :type       "Notify"
              :title      "no-params"
              :tags       []
              :exported   false
              :file "c"
              :line 2
              :environment nil
              :parameters {}}
        foo4 {:certname   "example.local"
              :resource   "4"
              :type       "File"
              :title      "/etc/Makefile"
              :tags       ["vivid"]
              :exported   false
              :file "d"
              :line 1
              :environment "DEV"
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
              :environment "DEV"
              :parameters {"random" "false"}}
        bar5 {:certname   "subset.local"
              :resource   "5"
              :type       "Notify"
              :title      "booyah"
              :tags       []
              :exported   false
              :file "d"
              :line 3
              :environment nil
              :parameters {"random" "false"}}
        foo6 {:certname   "example.local"
              :resource   "6"
              :type       "Mval"
              :title      "multivalue"
              :tags       []
              :exported   false
              :file "e"
              :line 1
              :environment "DEV"
              :parameters {"multi" ["one" "two" "three"]}}
        foo7 {:certname   "example.local"
              :resource   "7"
              :type       "Hval"
              :title      "hashvalue"
              :tags       []
              :exported   false
              :file "f"
              :line 1
              :environment "DEV"
              :parameters {"hash" {"foo" 5 "bar" 10}}}
        foo8 {:certname   "example.local"
              :resource   "8"
              :type       "Notify"
              :title      "semver"
              :tags       ["1.3.7+build.11.e0f985a"]
              :exported   false
              :file "f"
              :line 1
              :environment "DEV"
              :parameters {}}
        ]
    ;; ...and, finally, ready for testing.

    (let [version :v4]
      (testing (str "version " version " queries against SQL data")
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
          (is (= (set (query-resources version (s/query->sql version input)))
                 (set expect))
              (str "  " input " =>\n  " expect)))))))

(deftest paging-results
  (sql/insert-records
   :resource_params_cache
   {:resource "1" :parameters (db-serialize {"ensure" "file"
                                             "owner"  "root"
                                             "group"  "root"})}
   {:resource "2" :parameters (db-serialize {"random" "true"
                                             "enabled" "false"})}
   {:resource "3" :parameters (db-serialize {"hash" {"foo" 5 "bar" 10}
                                             "multi" ["one" "two" "three"]})}
   {:resource "4" :parameters (db-serialize {"ensure"  "present"
                                             "content" "contents"})})
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
                      {:id 1 :hash "foo" :api_version 1 :catalog_version "12" :certname "foo.local" :environment_id (ensure-environment "DEV")})
  (sql/insert-records :catalog_resources
                      {:catalog_id 1 :resource "1" :type "File" :title "alpha"   :exported true  :tags (to-jdbc-varchar-array []) :file "a" :line 1}
                      {:catalog_id 1 :resource "2" :type "File" :title "beta"    :exported true  :tags (to-jdbc-varchar-array []) :file "a" :line 4}
                      {:catalog_id 1 :resource "3" :type "File" :title "charlie" :exported true  :tags (to-jdbc-varchar-array []) :file "c" :line 2}
                      {:catalog_id 1 :resource "4" :type "File" :title "delta"   :exported false :tags (to-jdbc-varchar-array []) :file "d" :line 3})

  (let [r1 {:certname "foo.local" :resource "1" :type "File" :title "alpha"   :tags [] :exported true  :file "a" :line 1 :environment "DEV" :parameters {"ensure" "file" "group" "root" "owner" "root"}}
        r2 {:certname "foo.local" :resource "2" :type "File" :title "beta"    :tags [] :exported true  :file "a" :line 4 :environment "DEV" :parameters {"enabled" "false" "random" "true"}}
        r3 {:certname "foo.local" :resource "3" :type "File" :title "charlie" :tags [] :exported true  :file "c" :line 2 :environment "DEV" :parameters {"hash" {"bar" 10 "foo" 5} "multi" '("one" "two" "three")}}
        r4 {:certname "foo.local" :resource "4" :type "File" :title "delta"   :tags [] :exported false :file "d" :line 3 :environment "DEV" :parameters {"content" "contents" "ensure" "present"}}]

    (let [version :v4]
      (testing (str "version " version)

        (testing "include total results count"
          (let [expected 4
                actual   (:count (raw-query-resources version ["=" ["node" "active"] true] {:count? true}))]
            (is (= actual expected))))

        (testing "limit results"
          (doseq [[limit expected] [[1 1] [2 2] [100 4]]]
            (let [results (:result (raw-query-resources version ["=" ["node" "active"] true] {:limit limit}))
                  actual  (count results)]
              (is (= actual expected)))))

        (testing "order_by"
          (testing "rejects invalid fields"
            (is (thrown-with-msg?
                 IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order_by"
                 (:result (raw-query-resources version [] {:order_by [[:invalid-field :ascending]]})))))

          (testing "defaults to ascending"
            (let [expected [r1 r3 r4 r2]
                  actual   (:result (raw-query-resources version ["=" ["node" "active"] true]
                                                         {:order_by [[:line :ascending]]}))]
              (is (= actual expected))))

          (testing "alphabetical fields"
            (doseq [[order expected] [[:ascending  [r1 r2 r3 r4]]
                                      [:descending [r4 r3 r2 r1]]]]
              (testing order
                (let [actual (:result (raw-query-resources version ["=" ["node" "active"] true]
                                                           {:order_by [[:title order]]}))]
                  (is (= actual expected))))))

          (testing "numerical fields"
            (doseq [[order expected] [[:ascending  [r1 r3 r4 r2]]
                                      [:descending [r2 r4 r3 r1]]]]
              (testing order
                (let [actual (:result (raw-query-resources version ["=" ["node" "active"] true]
                                                           {:order_by [[:line order]]}))]
                  (is (= actual expected))))))

          (testing "multiple fields"
            (doseq [[[file-order line-order] expected] [[[:ascending :descending]  [r2 r1 r3 r4]]
                                                        [[:ascending :ascending]   [r1 r2 r3 r4]]
                                                        [[:descending :ascending]  [r4 r3 r1 r2]]
                                                        [[:descending :descending] [r4 r3 r2 r1]]]]
              (testing (format "file %s line %s" file-order line-order)
                (let [actual (:result (raw-query-resources version ["=" ["node" "active"] true]
                                                           {:order_by [[:file file-order]
                                                                       [:line line-order]]}))]
                  (is (= actual expected)))))))

        (testing "offset"
          (doseq [[order expected-sequences] [[:ascending [[0 [r1 r2 r3 r4]]
                                                           [1 [r2 r3 r4]]
                                                           [2 [r3 r4]]
                                                           [3 [r4]]
                                                           [4 []]]]
                                              [:descending [[0 [r4 r3 r2 r1]]
                                                            [1 [r3 r2 r1]]
                                                            [2 [r2 r1]]
                                                            [3 [r1]]
                                                            [4 []]]]]]
            (testing order
              (doseq [[offset expected] expected-sequences]
                (let [actual (:result (raw-query-resources version ["=" ["node" "active"] true]
                                                           {:order_by [[:title order]] :offset offset}))]
                  (is (= actual expected)))))))))))
