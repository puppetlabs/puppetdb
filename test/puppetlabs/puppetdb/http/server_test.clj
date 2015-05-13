(ns puppetlabs.puppetdb.http.server-test
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.testutils :refer [deftestseq]]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]))

;; This test file is for tests that aren't for any specific end-point.

(use-fixtures :each with-http-app)

(def versions [:v4])

(def c-t "application/json")

(deftestseq method-not-allowed
  [version versions]

  (testing "provides a useful error message"
    (let [endpoint (str "/" (name version) "/reports")
          request (header (request :post endpoint) "Accept" c-t)
          {:keys [status body]} (*app* request)]
      (is (= status http/status-bad-method))
      (is (= body (str "The POST method is not allowed for " endpoint))))))
