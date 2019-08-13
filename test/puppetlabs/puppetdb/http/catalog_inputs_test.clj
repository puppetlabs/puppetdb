(ns puppetlabs.puppetdb.http.catalog-inputs-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.command :refer [process-command!]]
   [puppetlabs.puppetdb.command.constants :refer [command-names]]
   [puppetlabs.puppetdb.time :as time]
   [puppetlabs.puppetdb.http :as http]
   [puppetlabs.puppetdb.testutils.catalog-inputs :refer [sample-input-cmds
                                                         validate-response-and-get-body
                                                         stringify-timestamp
                                                         process-replace-inputs]]
   [puppetlabs.puppetdb.testutils.http
    :refer [deftest-http-app
            query-response
            vector-param]]))

(def endpoints [[:v4 "/v4/catalog-inputs"]])

(deftest-http-app catalog-inputs-queries
  [[version endpoint] endpoints
   method [:get :post]]
  (let [input-cmds (sample-input-cmds)
        all-expected (stringify-timestamp (vals input-cmds))
        query-inputs #(-> (apply query-response method endpoint %&)
                          validate-response-and-get-body)]
    (process-replace-inputs (input-cmds "host-1"))
    (process-replace-inputs (input-cmds "host-2"))

    (let [inputs (query-inputs)]
      (is (= (count all-expected) (count inputs)))
      (is (= (set all-expected) (set inputs))))

    (let [cmd (input-cmds "host-1")
          exp (set (stringify-timestamp [cmd]))]
      (testing "certname match"
        (let [resp (query-inputs ["=" "certname" "host-1"])]
          (is (= (count exp) (count resp)))
          (is (= exp (set resp)))))
      (testing "producer_timestamp match"
        (let [resp (query-inputs ["=" "producer_timestamp" (:producer_timestamp cmd)])]
          (is (= (count exp) (count resp)))
          (is (= exp (set resp)))))
      (testing "catalog_uuid match"
        (let [resp (query-inputs ["=" "catalog_uuid" (:catalog_uuid cmd)])]
          (is (= (count exp) (count resp)))
          (is (= exp (set resp))))))

    (testing "projections"
      (let [resp (query-inputs ["extract" "certname"])]
        (is (= (count all-expected) (count resp)))
        (is (= (set (map #(hash-map :certname %) (keys input-cmds))) (set resp))))
      (let [resp (query-inputs ["extract" ["certname"] ["~" "certname" ""]])]
        (is (= (count all-expected) (count resp)))
        (is (= (set (map #(hash-map :certname %) (keys input-cmds))) (set resp))))
      (let [resp (query-inputs ["extract" "certname" ["=" "certname" "host-1"]])
            exp (->> (filter #(= "host-1" (:certname %)) all-expected)
                     (map #(select-keys % [:certname])))]
        (is (= (count exp) (count resp)))
        (is (= (set exp) (set resp))))
      (let [resp (query-inputs ["extract" [["function" "count"] "certname"]
                                ["group_by" "certname"]])
            exp (for [[_ cmd] input-cmds]
                  {:certname (:certname cmd)
                   :count 1})]
        (is (= (count exp) (count resp)))
        (is (= (set exp) (set resp)))))

    (testing "order_by"
      (doseq [field ["certname" "producer_timestamp" "catalog_uuid"]]
        (testing field
          (doseq [order [nil "asc" "desc"]]
            (let [fkey (keyword field)
                  order-by (vector-param method
                                         [(merge {:field field}
                                                 (when order {:order order}))])
                  resp (query-inputs ["extract" field] {:order_by order-by})
                  exp (->> (map #(select-keys % [fkey]) all-expected)
                           (sort-by fkey))
                  exp (cond-> exp (= order "desc") reverse)]
              (is (= (count all-expected) (count resp)))
              (is (= (set exp) (set resp))))))))

    (testing "trivial pagination"
      (let [order-by (vector-param method [(merge {:field "certname"})])
            resp (query-inputs nil {:order_by order-by
                                    :offset 0
                                    :limit 2})
            exp (->> (sort-by :certname all-expected) (take 2))]
        (is (= (count exp) (count resp)))
        (is (= (set exp) (set resp))))
      (let [order-by (vector-param method [(merge {:field "certname"})])
            resp (query-inputs nil {:order_by order-by
                                    :offset 2})
            exp (->> (sort-by :certname all-expected) (drop 2))]
        (is (= (count exp) (count resp)))
        (is (= (set exp) (set resp)))))

    (testing "simple subqueries -"
      (testing "\"in\" syntax"
        (let [resp (query-inputs ["extract" "certname"
                                  ["in" "certname"
                                   ["extract" "certname"
                                    ["select_catalog_inputs"
                                     ["=" "certname" "host-1"]]]]])
              exp (->> (filter #(= "host-1" (:certname %)) all-expected)
                       (map #(select-keys % [:certname])))]
          (is (= (count exp) (count resp)))
          (is (= (set exp) (set resp)))))

      (testing "(newer) \"from\" syntax"
       (let [resp (query-inputs ["extract" "certname"
                                 ["in" "certname"
                                  ["from" "catalog_inputs"
                                   ["extract" "certname"
                                    ["=" "certname" "host-1"]]]]])
             exp (->> (filter #(= "host-1" (:certname %)) all-expected)
                      (map #(select-keys % [:certname])))]
         (is (= (count exp) (count resp)))
         (is (= (set exp) (set resp)))))

      (testing "implicit"
        (let [resp (query-inputs ["extract" "certname"
                                  ["subquery" "catalog_inputs"
                                   ["=" "catalog_uuid" "80a1f1d2-1bd3-4f68-86db-74b3d0d96f95"]]])
              exp (->> (filter #(= "80a1f1d2-1bd3-4f68-86db-74b3d0d96f95" (:catalog_uuid %))
                               all-expected)
                       (map #(select-keys % [:certname])))]
          (is (= (count exp) (count resp)))
          (is (= (set exp) (set resp))))))))
