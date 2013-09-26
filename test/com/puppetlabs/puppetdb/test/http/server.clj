(ns com.puppetlabs.puppetdb.test.http.server
  (:require [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        [clojure.java.io :only [file]]))

(use-fixtures :each with-http-app)

(def c-t "application/json")

(deftest method-not-allowed
  (testing "provides a useful error message"
    (let [request (header (request :post "/v3/nodes") "Accept" c-t)
          {:keys [status body]} (*app* request)]
      (is (= status pl-http/status-bad-method))
      (is (= body "The POST method is not allowed for /v3/nodes")))))

(deftest resource-requests
  (testing "/ redirects to the dashboard"
    (let [request (request :get "/")
          {:keys [status headers]} (*app* request)]
      (is (= status pl-http/status-moved-temp))
      (is (= (headers "Location") "/dashboard/index.html"))))

  (testing "serving the dashboard works correctly"
    (let [request (request :get "/dashboard/index.html")
          {:keys [status body]} (*app* request)
          pwd (System/getProperty "user.dir")]
      (is (= status pl-http/status-ok))
      (is (instance? java.io.File body))
      (is (= (file pwd "resources/public/dashboard/index.html") body)))))
