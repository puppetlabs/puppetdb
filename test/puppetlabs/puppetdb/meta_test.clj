(ns puppetlabs.puppetdb.meta-test
  (:import (java.util.concurrent TimeUnit))
  (:require [clj-time.core :refer [ago seconds interval in-seconds]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils
             :refer [assert-success! dotestseq get-request]]
            [puppetlabs.puppetdb.meta :as meta]
            [puppetlabs.puppetdb.meta.version :as version]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.time :refer [parse-wire-datetime]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output]]
            [puppetlabs.puppetdb.middleware :as mid]))

(def endpoints [[:v1 "/v1"]])

(def parsed-body
  "Returns clojure data structures from the JSON body of
   ring response."
  (comp #(json/parse-string % true) :body))

(defn with-meta-app
  ([request]
   (with-meta-app request {} {}))
  ([request config-overrides]
   (with-meta-app request config-overrides {}))
  ([request config-overrides shared-globals-overrides]
   (let [app (mid/wrap-with-puppetdb-middleware
              (meta/build-app (fn [] shared-globals-overrides)
                              {:global (merge {:product-name "puppetdb"
                                               :update-server "FOO"}
                                              config-overrides)}))]
     (app request))))

(deftest test-latest-version
  (dotestseq [[version endpoint] endpoints]
    (with-redefs [version/update-info
                  (constantly
                   {"newer" true
                    "link" "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html"
                    "version" "100.0.0"})
                 version/version (constantly "99.0.0")]

      (testing "should return 'newer'->true if product is not specified"
        (let [response (-> (get-request (str endpoint "/version/latest"))
                           with-meta-app
                           parsed-body)]

          (are [expected response-key] (= expected
                                          (get response response-key))
               true :newer
               "100.0.0" :version
               "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" :link)))

      (testing "should return 'newer'->true if product is 'puppetdb"
        (let [response (-> (get-request (str endpoint "/version/latest"))
                           (with-meta-app {:product-name "puppetdb"})
                           parsed-body)]
          (are [expected response-key]
            (= expected (get response response-key))
            true :newer
            "100.0.0" :version
            "http://docs.puppetlabs.com/puppetdb/100.0/release_notes.html" :link)))

      (testing "should return 'newer'->false if product is 'pe-puppetdb"
        ;; it should *always* return false for pe-puppetdb
        ;; because
        ;; we don't even want to allow checking for
        ;; updates
        (let [response (-> (get-request (str endpoint "/version/latest"))
                           (with-meta-app {:product-name "pe-puppetdb"})
                           parsed-body)]
          (are [expected response-key] (= expected
                                          (get response response-key))
               false :newer
               "99.0.0" :version
               nil :link)))

      (testing "shouldn't log HTTP errors hitting update server at INFO"
        (with-log-output logz
          (let [response (-> (get-request (str endpoint "/version/latest"))
                             (with-meta-app
                               {:update-server "http://known.invalid.domain"}
                               {:scf-read-db *db*}))
                log-levels-emitted (set (map second @logz))]
            (is (nil? (log-levels-emitted :info)))))))))

(deftest test-latest-version
  (dotestseq [[version endpoint] endpoints]
    (testing "doesn't log update server HTTP errors at INFO"
      (with-test-db
        (with-log-output logz
          (let [response (-> (get-request (str endpoint "/version/latest"))
                             (with-meta-app
                               {:update-server "http://known.invalid.domain"}
                               {:scf-read-db *db*}))
                log-levels-emitted (set (map second @logz))]
            (is (nil? (log-levels-emitted :info)))))))))

(deftest server-time-response
  (dotestseq [[version endpoint] endpoints]
    (let [test-time (-> 1 seconds ago)
          response (-> (get-request (str endpoint "/server-time"))
                       with-meta-app)]
      (assert-success! response)
      (let [server-time (-> response
                            parsed-body
                            :server_time
                            parse-wire-datetime)]
        (is (> (in-seconds (interval test-time server-time)) 0))
        (is (> 5 (in-seconds (interval test-time server-time))))))))
