(ns puppetlabs.puppetdb.http.query-test
  (:require [puppetlabs.puppetdb.http.query :as sut]
            [clojure.test :as t]))

(t/deftest add-criteria-test
  (t/are [query criteria result]
      (= result (sut/add-criteria criteria query))
    ["=" "bar" 7] ["=" "foo" 42]
    ["and" ["=" "bar" 7] ["=" "foo" 42]]

    ["extract" ["foo" "bar"]] nil
    ["extract" ["foo" "bar"]]

    ["extract" ["foo" "bar"]] ["=" "foo" 42]
    ["extract" ["foo" "bar"] ["=" "foo" 42]]

    ["extract" ["foo" "bar"] nil] ["=" "foo" 42]
    ["extract" ["foo" "bar"] ["=" "foo" 42]]

    ["extract" ["foo" "bar"] ["=" "bar" 7]] ["=" "foo" 42]
    ["extract" ["foo" "bar"] ["and" ["=" "bar" 7] ["=" "foo" 42]]]

    ["from" "thing" ["extract" ["foo" "bar"] ["=" "bar" 7]]] ["=" "foo" 42]
    ["from" "thing" ["extract" ["foo" "bar"] ["and" ["=" "bar" 7] ["=" "foo" 42]]]]

    ["from" "thing"] ["=" "foo" 42]
    ["from" "thing" ["=" "foo" 42]]))
