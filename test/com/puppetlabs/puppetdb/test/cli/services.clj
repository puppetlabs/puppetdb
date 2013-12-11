(ns com.puppetlabs.puppetdb.test.cli.services
  (:import [java.security KeyStore])
  (:require clojure.string
            [fs.core :refer (absolute-path temp-file)]
            [com.puppetlabs.puppetdb.version]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]])
  (:use [com.puppetlabs.puppetdb.cli.services]
        [clojure.test]
        [clj-time.core :only [days hours minutes secs]]
        [clojure.java.io :only [resource]]
        [com.puppetlabs.time :only [to-secs to-minutes to-hours to-days period?]]))

(deftest update-checking
  (testing "should check for updates if running as puppetdb"
    (with-redefs [com.puppetlabs.puppetdb.version/update-info (constantly {:version "0.0.0" :newer true})]
      (with-log-output log-output
        (maybe-check-for-updates "puppetdb" "update-server!" {})
        (is (= 1 (count (logs-matching #"Newer version 0.0.0 is available!" @log-output)))))))

  (testing "should skip the update check if running as pe-puppetdb"
    (with-log-output log-output
      (maybe-check-for-updates "pe-puppetdb" "update-server!" {})
      (is (= 1 (count (logs-matching #"Skipping update check on Puppet Enterprise" @log-output)))))))

(deftest product-name-validation
  (doseq [product-name ["puppetdb" "pe-puppetdb"]]
    (testing (format "should accept %s and return it" product-name)
      (is (= product-name
             (normalize-product-name product-name)))))

  (doseq [product-name ["PUPPETDB" "PE-PUPPETDB" "PuppetDB" "PE-PuppetDB"]]
    (testing (format "should accept %s and return it lower-cased" product-name)
      (is (= (clojure.string/lower-case product-name)
             (normalize-product-name product-name)))))

  (testing "should disallow anything else"
    (is (thrown-with-msg? IllegalArgumentException #"product-name puppet is illegal"
          (normalize-product-name "puppet")))))

(deftest whitelisting
  (testing "should log on reject"
    (let [wl (temp-file)]
      (.deleteOnExit wl)
      (spit wl "foobar")
      (let [f (build-whitelist-authorizer (absolute-path wl))]
        (is (true? (f {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (false? (f {:ssl-client-cn "badguy"})))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate whitelist " @logz)))))))))
