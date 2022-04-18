(ns puppetlabs.puppetdb.http.catalogs-test
  (:require [cheshire.core :as json]
            [clojure.java.io :refer [resource reader]]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.catalogs :as catalogs]
            [puppetlabs.puppetdb.testutils :refer [get-request strip-hash]]
            [puppetlabs.puppetdb.testutils.http :refer [deftest-http-app
                                                        query-response
                                                        query-result
                                                        vector-param]]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat])
  (:import
   (java.net HttpURLConnection)))

(def endpoints [[:v4 "/v4/catalogs"]])

(def c-t "application/json")

;; TEST DATA

(def catalog1
  (-> (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
      json/parse-string
      keywordize-keys))

(def catalog2 (merge catalog1
                 {:certname "host2.localdomain"
                  :producer_timestamp "2010-07-10T22:33:54.781Z"
                  :transaction_uuid "00000000-0000-0000-0000-000000000000"
                  :environment "PROD"}))

(def queries
  {["=" "certname" "myhost.localdomain"]
   [catalog1]

   ["=" "certname" "host2.localdomain"]
   [catalog2]

   ["<" "producer_timestamp" "2014-07-10T22:33:54.781Z"]
   [catalog2]

   ["=" "environment" "PROD"]
   [catalog2]

   ["~" "environment" "PR"]
   [catalog2]

   nil
   [catalog1 catalog2]})

(def paging-options
  {[{:field "environment"}]
   [catalog1 catalog2]

   [{:field "producer_timestamp"}]
   [catalog2 catalog1]

   [{:field "certname"}]
   [catalog2 catalog1]

   [{:field "transaction_uuid"}]
   [catalog2 catalog1]

   [{:field "certname" :order "desc"}]
   [catalog1 catalog2]})

;; HELPERS

(defn extract-tags
  [xs]
  (sort (flatten (map :tags (flatten (map :resources xs))))))

;; TESTS

(deftest-http-app catalog-queries
  [[version endpoint] endpoints
   method [:get :post]]
  (testcat/replace-catalog catalog1)
  (testcat/replace-catalog catalog2)
  (testing "catalog endpoint is queryable"
    (doseq [q (keys queries)]
      (let [{:keys [status body]} (query-response method endpoint q)
            response-body (strip-hash (json/parse-string (slurp body) true))
            expected (get queries q)]
        (is (= (count expected) (count response-body)))
        (is (= (sort (map :certname expected)) (sort (map :certname response-body))))
        (is (= (extract-tags expected)
               (extract-tags (catalogs/catalogs-query->wire-v9 response-body)))))))

  (testing "projection queries"
    (are [query expected]
        (= expected
           (-> (reader (:body (query-response method endpoint query)))
               (json/parse-stream true)
               strip-hash
               set))

      ["extract" "certname"]
      #{{:certname "myhost.localdomain"}
        {:certname "host2.localdomain"}}

         ["extract" ["certname"] ["~" "certname" ""]]
         #{{:certname "myhost.localdomain"}
           {:certname "host2.localdomain"}}

         ["extract" ["edges"] ["=" "certname" "host2.localdomain"]]
         #{{:edges (merge {:href "/pdb/query/v4/catalogs/host2.localdomain/edges"}
                          {:data [{:source_type "Apt::Pin"
                                   :source_title "puppetlabs"
                                   :target_type "File"
                                   :target_title "/etc/apt/preferences.d/puppetlabs.pref"
                                   :relationship "contains"}]})}}

         ["extract" [["function" "count"] "environment"]
          ["~" "certname" ""]
          ["group_by" "environment"]]
         #{{:environment "DEV"
            :count 1}
           {:environment "PROD"
            :count 1}}))

  (testing "top-level extract works with catalogs"
    (let [query ["extract" ["certname"] ["~" "certname" ""]]
          {:keys [body]} (query-response method endpoint query)
          response-body (strip-hash (json/parse-stream (reader body) true))
          expected [{:certname "myhost.localdomain"}
                    {:certname "host2.localdomain"}]]
      (is (= (sort-by :certname expected) (sort-by :certname response-body)))))

  (testing "paging options"
    (doseq [p (keys paging-options)]
      (testing (format "checking ordering %s" p)
        (let [{:keys [status body]} (query-response
                                      method endpoint nil
                                      {:order_by (vector-param method p)})
            response-body (strip-hash (json/parse-stream (reader body) true))
            expected (get paging-options p)]
        (is (= (map :certname expected) (map :certname response-body)))))))

  (testing "endpoint is still responsive to old-style node queries"
    (let [{:keys [body]} (query-response method (str endpoint "/myhost.localdomain"))
          response-body  (json/parse-string body true)]
      (is (= "myhost.localdomain" (:certname response-body))))))

(deftest-http-app catalog-subqueries
  [[version endpoint] endpoints
   method [:get :post]]

  (testcat/replace-catalog catalog1)
  (testcat/replace-catalog catalog2)

  (are [query expected]
      (= expected
         (query-result method endpoint query {} strip-hash))

    ;;;;;;;;;;
    ;; Resources
    ;;;;;;;;;;

    ;; In syntax: select_resources
    ["extract" "certname"
     ["in" "certname"
      ["extract" "certname"
       ["select_resources"
        ["=" "type" "Apt::Pin"]]]]]
    #{{:certname "myhost.localdomain"}
      {:certname "host2.localdomain"}}

    ["extract" "certname"
     ["in" "certname"
      ["extract" "certname"
       ["select_resources"
        ["in" "type" ["array" ["Apt::Pin"]]]]]]]
    #{{:certname "myhost.localdomain"}
      {:certname "host2.localdomain"}}

    ;; In syntax: from
    ["extract" "certname"
     ["in" "certname"
      ["from" "resources"
       ["extract" "certname"
        ["=" "type" "Apt::Pin"]]]]]
    #{{:certname "myhost.localdomain"}
      {:certname "host2.localdomain"}}

    ;; Implicit subquery syntax
    ["extract" "certname"
     ["subquery" "resources"
      ["=" "type" "Apt::Pin"]]]
    #{{:certname "myhost.localdomain"}
      {:certname "host2.localdomain"}}

    ["extract" "certname"
     ["subquery" "resources"
      ["in" "type" ["array" ["Apt::Pin"]]]]]
    #{{:certname "myhost.localdomain"}
      {:certname "host2.localdomain"}}

    ;;;;;;;;;
    ;; Edges subqueries
    ;;;;;;;;;

    ;; In operator: select_edges
    ["extract" "certname"
     ["in" "certname"
      ["extract" "certname"
       ["select_edges"
        ["=" "target_type" "File"]]]]]
    #{{:certname "host2.localdomain"}
      {:certname "myhost.localdomain"}}

    ;; In operator: from edges
    ["extract" "certname"
     ["in" "certname"
      ["from" "edges"
       ["extract" "certname"
        ["=" "target_type" "File"]]]]]
    #{{:certname "host2.localdomain"}
      {:certname "myhost.localdomain"}}

    ;; Implicit query
    ["extract" "certname"
     ["subquery" "edges"
      ["=" "target_type" "File"]]]
    #{{:certname "host2.localdomain"}
      {:certname "myhost.localdomain"}}

    ["extract" "certname"
     ["subquery" "edges"
      ["in" "target_type" ["array" ["File"]]]]]
    #{{:certname "host2.localdomain"}
      {:certname "myhost.localdomain"}}))

(def no-parent-endpoints [[:v4 "/v4/catalogs/foo/edges"]
                          [:v4 "/v4/catalogs/foo/resources"]])

(deftest-http-app unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= HttpURLConnection/HTTP_NOT_FOUND status))
    (is (= {:error "No information is known about catalog foo"} (json/parse-string body true)))))
