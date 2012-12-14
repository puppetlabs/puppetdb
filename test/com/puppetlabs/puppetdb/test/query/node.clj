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

(defn retrieve-nodes
  "Search for nodes based on an uncompiled query."
  [filter-expr]
  (let [query (node/v2-query->sql filter-expr)]
    (mapv :name (node/query-nodes query))))

(deftest query-nodes
  (let [names     #{"node_a" "node_b" "node_c" "node_d" "node_e"}
        timestamp (to-timestamp (now))]
    (doseq [name names]
      (sql/insert-record :certnames {:name name})
      (sql/insert-record :certname_facts_metadata {:certname name :timestamp timestamp}))

    (sql/insert-records
      :certname_facts
      {:certname "node_a" :name "kernel" :value "Linux"}
      {:certname "node_b" :name "kernel" :value "Linux"}
      {:certname "node_c" :name "kernel" :value "Darwin"}
      {:certname "node_d" :name "uptime_seconds" :value "10000"})

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
