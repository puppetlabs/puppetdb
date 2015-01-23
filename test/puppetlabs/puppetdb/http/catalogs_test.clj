(ns puppetlabs.puppetdb.http.catalogs-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [clojure.java.io :refer [resource reader]]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq strip-hash]]
            [puppetlabs.puppetdb.fixtures :as fixt]))

(def endpoints [[:v4 "/v4/catalogs"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def c-t "application/json")

(defn get-response
  ([endpoint]
     (get-response endpoint nil))
  ([endpoint node]
     (fixt/*app* (get-request (str endpoint "/" node))))
  ([endpoint node query]
   (fixt/*app* (get-request (str endpoint "/" node) query)))
  ([endpoint node query params]
   (fixt/*app* (get-request (str endpoint "/" node) query params))))


(def catalog1
  (-> (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
      json/parse-string
      keywordize-keys))

(def catalog2 (merge catalog1
                 {:name "host2.localdomain"
                  :producer_timestamp "2010-07-10T22:33:54.781Z"
                  :transaction_uuid "000000000000000000000000000"
                  :environment "PROD"}))

(def queries
  {["=" "name" "myhost.localdomain"]
   [catalog1]

   ["=" "name" "host2.localdomain"]
   [catalog2]

   ["<" "producer_timestamp" "2014-07-10T22:33:54.781Z"]
   [catalog2]

   ["=" "environment" "PROD"]
   [catalog2]

   ["~" "environment" "PR"]
   [catalog2]

   []
   [catalog1 catalog2]})

(def paging-options
  {{:order_by (json/generate-string [{:field "environment"}])}
   [catalog1 catalog2]

   {:order_by (json/generate-string [{:field "producer_timestamp"}])}
   [catalog2 catalog1]

   {:order_by (json/generate-string [{:field "name"}])}
   [catalog2 catalog1]

   {:order_by (json/generate-string [{:field "transaction_uuid"}])}
   [catalog2 catalog1]

   {:order_by (json/generate-string [{:field "name" :order "desc"}])}
   [catalog1 catalog2]})

(defn extract-tags
  [xs]
  (sort (flatten (map :tags (flatten (map :resources xs))))))

(deftestseq v4-catalog-queries
  [[version endpoint] [[:v4 "/v4/catalogs"]]]
  (testcat/replace-catalog (json/generate-string catalog1))
  (testcat/replace-catalog (json/generate-string catalog2))
  (testing "v4 catalog endpoint is queryable"
    (doseq [q (keys queries)]
      (let [{:keys [status body] :as response} (get-response endpoint nil q)
            response-body (strip-hash (json/parse-stream (reader body) true))
            expected (get queries q)]
        (is (= (count expected) (count response-body)))
        (is (= (sort (map :name expected)) (sort (map :name response-body))))
        (is (= (extract-tags expected) (extract-tags response-body))))))

  (testing "top-level extract works with catalogs"
    (let [query ["extract" ["name"] ["~" "name" ""]]
          {:keys [body]} (get-response endpoint nil query)
          response-body (strip-hash (json/parse-stream (reader body) true))
          expected [{:name "myhost.localdomain"}
                    {:name "host2.localdomain"}]]
      (is (= (sort-by :name expected) (sort-by :name response-body)))))

  (testing "paging options"
    (doseq [p (keys paging-options)]
      (testing (format "checking ordering %s" p)
      (let [{:keys [status body] :as response} (get-response endpoint nil nil p)
            response-body (strip-hash (json/parse-stream (reader body) true))
            expected (get paging-options p)]
        (is (= (map :name expected) (map :name response-body)))))))

  (testing "/v4 endpoint is still responsive to old-style node queries"
    (let [{:keys [body]} (get-response "/v4/catalogs" "myhost.localdomain")
          response-body  (json/parse-string body true)]
      (is (= "myhost.localdomain" (:name response-body))))))
