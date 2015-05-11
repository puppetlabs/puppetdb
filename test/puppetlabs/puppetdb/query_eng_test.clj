(ns puppetlabs.puppetdb.query-eng-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.query-eng.engine :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.scf.storage-utils :as su]))

(use-fixtures :each fixt/with-test-db)

(deftest test-plan-sql
  (are [sql plan] (= sql (plan->sql plan))

       [:or [:= :foo "?"]]
       (->BinaryExpression := :foo "?")

       (su/sql-regexp-match :foo)
       (->RegexExpression :foo "?")

       (su/sql-array-query-string :foo)
       (->ArrayBinaryExpression :foo "?")

       [:and [:or [:= :foo "?"]] [:or [:= :bar "?"]]]
       (->AndExpression [(->BinaryExpression := :foo "?")
                         (->BinaryExpression := :bar "?")])

       [:or [:or [:= :foo "?"]] [:or [:= :bar "?"]]]
       (->OrExpression [(->BinaryExpression := :foo "?")
                        (->BinaryExpression := :bar "?")])

       [:not [:or [:= :foo "?"]]]
       (->NotExpression (->BinaryExpression := :foo "?"))

       [:is :foo nil]
       (->NullExpression :foo true)

       [:is-not :foo nil]
       (->NullExpression :foo false)

       "SELECT table.foo AS foo FROM table WHERE (1 = 1)"
       (map->Query {:projections {"foo" {:type :string
                                         :queryable? true
                                         :field :table.foo}}
                    :alias "thefoo"
                    :subquery? false
                    :where (->BinaryExpression := 1 1)
                    :selection {:from [:table]}
                    :source-table "table"})))

(deftest test-extract-params

  (are [expected plan] (= expected (extract-all-params plan))

       {:plan (->AndExpression [(->BinaryExpression "="  "foo" "?")
                                (->RegexExpression "bar" "?")
                                (->NotExpression (->BinaryExpression "=" "baz" "?"))])
        :params ["1" "2" "3"]}
       (->AndExpression [(->BinaryExpression "=" "foo" "1")
                         (->RegexExpression "bar" "2")
                         (->NotExpression (->BinaryExpression "=" "baz" "3"))])

       {:plan (map->Query {:where (->BinaryExpression "=" "foo" "?")})
        :params ["1"]}
       (map->Query {:where (->BinaryExpression "=" "foo" "1")})))

(deftest test-expand-user-query
  (is (= [["=" "prop" "foo"]]
         (expand-user-query [["=" "prop" "foo"]])))

  (is (= [["=" "prop" "foo"]
          ["in" "certname"
           ["extract" "certname"
            ["select_nodes"
             ["and"
              ["null?" "deactivated" true]
              ["null?" "expired" true]]]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["node" "active"] true]])))
  (is (= [["=" "prop" "foo"]
          ["in" "resource"
           ["extract" "res_param_resource"
            ["select_params"
             ["and"
              ["=" "res_param_name" "bar"]
              ["=" "res_param_value" "\"baz\""]]]]]]
         (expand-user-query [["=" "prop" "foo"]
                             ["=" ["parameter" "bar"] "baz"]]))))


(deftest test-valid-query-fields
  (is (thrown-with-msg? IllegalArgumentException
                        #"'foo' is not a queryable object for resources, known queryable objects are.*"
                        (compile-user-query->sql resources-query ["=" "foo" "bar"]))))
