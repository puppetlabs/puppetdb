(ns com.puppetlabs.puppetdb.test.http.version
  (:import (java.util.concurrent TimeUnit))
  (:require [com.puppetlabs.cheshire :as json]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.http.version :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [com.puppetlabs.puppetdb.version :as version]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(def parsed-body
  "Returns clojure data structures from the JSON body of
   ring response."
  (comp json/parse-string :body))

(defn app-with-update-server
  "Issues `request` with `overrides` merged into the request map. Useful for
   overriding things that the middleware would put in the ring request map before
   the handlers processes the request."
  [overrides request]
  (fixt/with-http-app (merge {:update-server "FOO"} overrides)
    (fn []
      (fixt/*app* request))))

(deftest test-latest-version
  (with-redefs [version/update-info
                (constantly
                 {"newer" true
                  "link" "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html"
                  "version" "100.0.0"})
                version/version (constantly "99.0.0")]
    (testing "should return 'newer'->true if product is not specified"
      (let [api-response (parsed-body (latest-version (fixt/internal-request)))
            v1-response (parsed-body (app-with-update-server {} (tu/get-request "/v1/version/latest")))
            v2-response (parsed-body (app-with-update-server {} (tu/get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {} (tu/get-request "/v3/version/latest")))]

        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v1-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->true if product is 'puppetdb"
      (let [api-response (parsed-body (latest-version (fixt/internal-request {:product-name "puppetdb"} {})))
            v1-response (parsed-body (app-with-update-server {:product-name "puppetdb"} (tu/get-request "/v1/version/latest")))
            v2-response (parsed-body (app-with-update-server {:product-name "puppetdb"} (tu/get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {:product-name "puppetdb"} (tu/get-request "/v3/version/latest")))]
        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v1-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->false if product is 'pe-puppetdb"
      ;; it should *always* return false for pe-puppetdb because
      ;; we don't even want to allow checking for updates
      (let [api-response (parsed-body (latest-version (fixt/internal-request {:product-name "pe-puppetdb"} {})))
            v1-response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                             (tu/get-request "/v1/version/latest")))
            v2-response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                             (tu/get-request "/v2/version/latest")))
            v3-response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                             (tu/get-request "/v3/version/latest")))]
        (are [expected response-key] (= expected
                                        (get api-response response-key)
                                        (get v1-response response-key)
                                        (get v2-response response-key)
                                        (get v3-response response-key))
             false "newer"
             "99.0.0" "version"
             nil "link")))))
