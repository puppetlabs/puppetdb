(ns com.puppetlabs.puppetdb.test.http.v3.version
  (:require [com.puppetlabs.puppetdb.version :as version]
            [cheshire.core :as json])
  (:use clojure.test
        com.puppetlabs.puppetdb.fixtures
        ring.mock.request
        [com.puppetlabs.puppetdb.testutils :only [get-request]]))

(def endpoint "/v3/version")

(use-fixtures :each with-test-db with-http-app)

(def content-type-json "application/json")

(defn get-response
  ([]
    (get-response {}))
  ([globals-overrides]
    (with-http-app (merge {:update-server "FOO"} globals-overrides)
      (fn []
        (with-redefs [version/update-info
                      (constantly
                        {"newer" true
                         "link" "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html"
                         "version" "100.0.0"})
                      version/version (constantly "99.0.0")]
          (json/parse-string (:body (*app* (get-request (str endpoint "/latest"))))))))))

(deftest latest-version-response
  (testing "should return 'newer'->true if product is not specified"
    (let [response (get-response)]
      (is (= true (response "newer")))
      (is (= "100.0.0" (response "version")))
      (is (= "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" (response "link")))))
  (testing "should return 'newer'->true if product is 'puppetdb"
    (let [response (get-response {:product-name "puppetdb"})]
      (is (= true (response "newer")))
      (is (= "100.0.0" (response "version")))
      (is (= "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" (response "link")))))
  (testing "should return 'newer'->false if product is 'pe-puppetdb"
    ;; it should *always* return false for pe-puppetdb because
    ;; we don't even want to allow checking for updates
    (let [response (get-response {:product-name "pe-puppetdb"})]
      (is (= false (response "newer")))
      (is (= "99.0.0" (response "version")))
      (is (= nil (response "link"))))))
