(ns puppetlabs.puppetdb.http.version-test
  (:import (java.util.concurrent TimeUnit))
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.http.version :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]
            [puppetlabs.puppetdb.version :as version]))

(use-fixtures :each fixt/with-test-db fixt/with-test-mq fixt/with-http-app)

(def endpoints [[:v4 "/v4/version"]])

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

(deftestseq test-latest-version
  [[version endpoint] endpoints]

  (with-redefs [version/update-info
                (constantly
                 {"newer" true
                  "link" "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html"
                  "version" "100.0.0"})
                version/version (constantly "99.0.0")]
    (testing "should return 'newer'->true if product is not specified"
      (let [response (parsed-body (app-with-update-server {} (get-request (str endpoint "/latest"))))]

        (are [expected response-key] (= expected
                                        (get response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->true if product is 'puppetdb"
      (let [response (parsed-body (app-with-update-server {:product-name "puppetdb"}
                                                          (get-request (str endpoint "/latest"))))]
        (are [expected response-key] (= expected
                                        (get response response-key))
             true "newer"
             "100.0.0" "version"
             "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" "link")))
    (testing "should return 'newer'->false if product is 'pe-puppetdb"
      ;; it should *always* return false for pe-puppetdb because
      ;; we don't even want to allow checking for updates
      (let [response (parsed-body (app-with-update-server {:product-name "pe-puppetdb"}
                                                          (get-request (str endpoint "/latest"))))]
        (are [expected response-key] (= expected
                                        (get response response-key))
             false "newer"
             "99.0.0" "version"
             nil "link")))))
