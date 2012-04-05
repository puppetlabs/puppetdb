(ns com.puppetlabs.puppetdb.test.query.node
  (:require [clojure.set :as set]
            [com.puppetlabs.puppetdb.query.node :as node]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-timestamp]]
        [clojure.math.combinatorics :only [combinations]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(deftest fetch-all
  (sql/insert-records
   :certnames
   {:name "foo"}
   {:name "bar"}
   {:name "baz"}
   {:name "quux"}
   {:name "something"})

  (testing "should return all the certnames in the database"
    (is (= (node/fetch-all)
           ["bar" "baz" "foo" "quux" "something"])) ))

(defn retrieve-nodes
  "Search for nodes based on an uncompiled query."
  [filter-expr]
  (let [query (node/query->sql filter-expr)]
    (node/search query)))

(deftest search
  (let [names     #{"node_a" "node_b" "node_c" "node_d" "node_e"}
        timestamp (to-timestamp (now))]
    (doseq [name names]
      (sql/insert-record :certnames {:name name})
      (sql/insert-record :certname_facts_metadata {:certname name :timestamp timestamp}))

    (sql/insert-records
      :certname_facts
      {:certname "node_a" :fact "kernel" :value "Linux"}
      {:certname "node_b" :fact "kernel" :value "Linux"}
      {:certname "node_c" :fact "kernel" :value "Darwin"}
      {:certname "node_d" :fact "uptime_seconds" :value "10000"})

    (let [test-cases {["=" ["fact" "kernel"] "Linux"]
                       #{"node_a" "node_b"}
                       ["=" ["fact" "kernel"] "Darwin"]
                       #{"node_c"}
                       ["=" ["fact" "kernel"] "Nothing"]
                       #{}
                       ["=" ["fact" "uptime"] "Linux"]
                       #{}
                       ["=" ["fact" "uptime_seconds"] "10000"]
                       #{"node_d"}}]
      (doseq [size (range 1 (inc (count test-cases)))
              terms (combinations test-cases size)
              :let [exprs      (map first terms)
                    results    (map (comp set last) terms)
                    and-expr   (cons "and" exprs)
                    and-result (apply set/intersection results)
                    or-expr    (cons "or" exprs)
                    or-result  (apply set/union results)
                    not-expr   (cons "not" exprs)
                    not-result (apply set/difference names results)]]
        (is (= (retrieve-nodes and-expr)
               (sort and-result))
            (format "%s => %s" and-expr and-result))
        (is (= (retrieve-nodes or-expr)
               (sort or-result))
            (format "%s => %s" or-expr or-result))
        (is (= (retrieve-nodes not-expr)
               (sort not-result))
            (format "%s => %s" not-expr not-result))))))

(deftest compile-predicate->sql
  (testing "compiling '=' queries"
    (testing "should generate fact queries"
      (doseq [[op path value :as term] [["=" ["fact" "kernel"] "Linux"]
                                        ["=" ["fact" "uptime_days"] "200"]
                                        ["=" ["fact" "architecture"] "i386"]]]
        (is (= (node/compile-predicate->sql term)
               [(str "(SELECT DISTINCT certname_facts.certname FROM certname_facts "
                     "WHERE ((certname_facts.fact = ?) AND (certname_facts.value = ?)))") (last path) value]))))

    (testing "should generate queries for nodes based on activeness"
      (is (= (node/compile-predicate->sql ["=" ["node" "active"] true])
             [(str "(SELECT DISTINCT certnames.name AS certname FROM certnames "
                   "WHERE (certnames.deactivated IS NULL))")])))

    (testing "should reject any other sort of queries"
      (doseq [term [["=" "kernel" "Linux"]
                    ["=" ["node" "name"] "foo"]
                    ["=" ["facts" "kernel"] "linux"]
                    ["=" ["foo" "bar"] "baz"]]]
        (is (thrown-with-msg? IllegalArgumentException #"is not a valid query term"
              (node/compile-predicate->sql term))))))

  (testing "joining terms together"
    (let [term1 ["=" ["fact" "uptime_days"] "200"]
          term2 ["=" ["fact" "kernel"] "Linux"]
          [query1 & params1] (node/compile-predicate->sql term1)
          [query2 & params2] (node/compile-predicate->sql term2)]

      (doseq [[op result] {"and" "(SELECT DISTINCT certname FROM %s resources_0 NATURAL JOIN %s resources_1)"
                           "or" "(%s UNION %s)"
                           "not" (str "(SELECT DISTINCT lhs.name AS certname FROM certnames lhs "
                                      "LEFT OUTER JOIN (%s UNION %s) rhs ON lhs.name = rhs.certname "
                                      "WHERE (rhs.certname IS NULL))")}]
        (testing (str "should be able to join using " op)
          (is (= (node/compile-predicate->sql [op term1 term2])
                 (apply vector
                        (format result query1 query2)
                        (concat params1 params2)))
              [op term1 term2]))))

    (doseq [op ["and" "or" "not"]]
      (testing (str "should fail if no terms are specified when using " op)
        (is (thrown-with-msg? IllegalArgumentException #"requires at least one term"
              (node/compile-predicate->sql [op])))))))
