(ns com.puppetlabs.puppetdb.test.http.server
  (:require [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures))

(use-fixtures :each with-http-app)

(def c-t "application/json")

(deftest method-not-allowed
  (testing "provides a useful error message"
    (let [request (header (request :post "/v1/nodes") "Accept" c-t)
          {:keys [status body]} (*app* request)]
      (is (= status pl-http/status-bad-method))
      (is (= body "The POST method is not allowed for /v1/nodes")))))
