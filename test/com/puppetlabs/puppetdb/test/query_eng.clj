(ns com.puppetlabs.puppetdb.test.query-eng
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.query-eng :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.scf.storage-utils :as su]))

(use-fixtures :each fixt/with-test-db)

(deftest test-parenthize
  (is (= " ( foo ) " (parenthize true "foo")))
  (is (= "foo" (parenthize false "foo"))))

(deftest test-plan-sql
  (are [sql plan] (= sql (plan->sql plan))

       "foo = ?"
       (->EqualsExpression "foo" "?")

       (su/sql-regexp-match "foo")
       (->RegexExpression "foo" "?")

       (su/sql-array-query-string "foo")
       (->ArrayEqualsExpression "foo" "?")

       "foo = ? AND bar = ?"
       (->AndExpression [(->EqualsExpression "foo" "?")
                         (->EqualsExpression "bar" "?")])

       "foo = ? OR bar = ?"
       (->OrExpression [(->EqualsExpression "foo" "?")
                        (->EqualsExpression "bar" "?")])

       "NOT ( foo = ? )"
       (->NotExpression (->EqualsExpression "foo" "?"))

       "foo IS NULL"
       (->NullExpression "foo" true)

       "foo IS NOT NULL"
       (->NullExpression "foo" false)

       "SELECT thefoo.foo FROM ( select foo from table ) AS thefoo WHERE 1 = 1"
       (map->Query {:project {"foo" :string}
                    :alias "thefoo"
                    :subquery? false
                    :where (->EqualsExpression 1 1)
                    :source "select foo from table"})))

(deftest test-extract-params

  (are [expected plan] (= expected (extract-all-params plan))

       {:plan (->AndExpression [(->EqualsExpression "foo" "?")
                                (->RegexExpression "bar" "?")
                                (->NotExpression (->EqualsExpression "baz" "?"))])
        :params ["1" "2" "3"]}
       (->AndExpression [(->EqualsExpression "foo" "1")
                         (->RegexExpression "bar" "2")
                         (->NotExpression (->EqualsExpression "baz" "3"))])

       {:plan (map->Query {:where (->EqualsExpression "foo" "?")})
        :params ["1"]}
       (map->Query {:where (->EqualsExpression "foo" "1")})))

(deftest test-expand-user-query
  (is (= [["=" "prop" "foo"]]
         (expand-user-query [["=" "prop" "foo"]])))

  (is (= [["=" "prop" "foo"]
          ["in" "certname"
           ["extract" "certname"
            ["select-nodes"
             ["nil?" "deactivated" false]]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["node" "active"] true]])))
  (is (= [["=" "prop" "foo"]
          ["in" "resource"
           ["extract" "res_param_resource"
            ["select-params"
             ["and"
              ["=" "res_param_name" "bar"]
              ["=" "res_param_value" "\"baz\""]]]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["parameter" "bar"] "baz"]]))))
