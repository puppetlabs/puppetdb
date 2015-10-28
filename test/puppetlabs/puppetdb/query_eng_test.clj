(ns puppetlabs.puppetdb.query-eng-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.query-eng.engine :refer :all]
            [puppetlabs.puppetdb.query-eng :refer [entity-fn-idx]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.fixtures :refer [*app* *db* with-test-db]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.testutils :refer [get-request parse-result]]
            [puppetlabs.puppetdb.testutils.http :refer [deftest-http-app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage-utils :as su]))

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
                             ["=" ["parameter" "bar"] "baz"]])))

  (testing "implicit subqueries"
    (are [context in out]
      (= (expand-user-query
          (push-down-context context in)) out)

      ;; Simplistic 1 column examples (catalogs example)
      (map->Query {:relationships
                   {"resources" {:columns ["certname"]}}})
      ["subquery" "resources"
       ["=" "type" "Class"]]
      ["in" ["certname"]
       ["extract" ["certname"]
        ["select_resources"
         ["=" "type" "Class"]]]]

      ;; Where local and foreign differ (resources example)
      (map->Query {:relationships
                   {"environments" {:local-columns ["environment"]
                                    :foreign-columns ["name"]}}})
      ["subquery" "environments"
       ["=" "name" "production"]]
      ["in" ["environment"]
       ["extract" ["name"]
        ["select_environments"
         ["=" "name" "production"]]]]

      ;; Two column examples (fact-contents example)
      (map->Query {:relationships
                   {"facts" {:columns ["certname" "name"]}}})
      ["subquery" "facts"
       ["=" "name" "networking"]]
      ["in" ["certname" "name"]
       ["extract" ["certname" "name"]
        ["select_facts"
         ["=" "name" "networking"]]]])))

(deftest test-extract-with-no-subexpression-compiles
  (is (re-find #"SELECT .*certname FROM reports"
               (->> ["extract" "certname"]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT .*certname FROM reports"
               (->> ["extract" ["certname"]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT count\(certname\) AS count FROM reports"
               (->> ["extract" [["function" "count" "certname"]]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first)))
  (is (re-find #"SELECT .*certname AS certname, count\(\*\) .* FROM reports"
               (->> ["extract" [["function" "count"] "certname"] ["group_by" "certname"]]
                    (compile-user-query->sql reports-query)
                    :results-query
                    first))))

(deftest test-valid-query-fields
  (is (thrown-with-msg? IllegalArgumentException
                        #"'foo' is not a queryable object for resources, known queryable objects are.*"
                        (compile-user-query->sql resources-query ["=" "foo" "bar"]))))

(deftest-http-app query-recs-are-swappable
  [version [:v4]
   endpoint ["/v4/fact-names"]
   :let [facts1 {"domain" "testing.com"
                  "hostname" "foo1"
                  "kernel" "Linux"
                  "operatingsystem" "Debian"
                  "uptime_seconds" "4000"}
         facts2 {"domain" "testing.com"
                 "hostname" "foo2"
                 "kernel" "Linux"
                 "operatingsystem" "RedHat"
                 "uptime_seconds" "6000"}
         facts3 {"domain" "testing.com"
                 "hostname" "foo3"
                 "kernel" "Darwin"
                 "operatingsystem" "Darwin"
                 "memorysize" "16.00 GB"}
         initial-idx @entity-fn-idx]]

  (with-transacted-connection *db*
    (scf-store/add-certname! "foo1")
    (scf-store/add-certname! "foo2")
    (scf-store/add-certname! "foo3")
    (scf-store/add-facts! {:certname "foo2"
                           :values facts2
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-facts! {:certname "foo3"
                           :values facts3
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/deactivate-node! "foo1")
    (scf-store/add-facts! {:certname "foo1"
                           :values  facts1
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)}))

  (let [expected-result ["domain" "hostname" "kernel" "memorysize"
                         "operatingsystem" "uptime_seconds"]]
    (testing "fact-names behaves normally"
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result expected-result))))

    (testing "query rec is modifiable"
      (swap! entity-fn-idx assoc-in [:fact-names :munge] (fn [_ _] identity))

      (swap! entity-fn-idx
             assoc-in [:fact-names :rec :projections "depth"] {:type :integer
                                                               :queryable? true
                                                               :field :depth})
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result (map #(hash-map :name % :depth 0) expected-result)))))

    (reset! entity-fn-idx initial-idx)
    (testing "fact-names back to normal"
      (let [request (get-request endpoint)
            {:keys [status body]} (*app* request)
            result (vec (parse-result body))]
        (is (= status http/status-ok))
        (is (= result expected-result))))))
