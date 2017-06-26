(ns puppetlabs.puppetdb.http.graphql-test
  (:require [cheshire.core :as json]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [parse-result]]
            [puppetlabs.puppetdb.testutils.http
             :refer [deftest-http-app
                     query-response]]
            [clojure.java.io :as io]))

(def graphql-endpoint [[:v4 "/v4/graphql"]])

(deftest-http-app graphql-endpoint-tests
  [[version endpoint] graphql-endpoint
   method [:get :post]]

  (testing "should return 200 with message about experimental endpoint"
    (let [{:keys [status body]} (query-response method endpoint)
          result (parse-result body)]
      (prn result)
      (is (= status http/status-ok))
      (is (false? (empty? result))))))
