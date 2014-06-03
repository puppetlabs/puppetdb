(ns com.puppetlabs.puppetdb.test.cli.services
  (:import [java.security KeyStore])
  (:require clojure.string
            [fs.core :as fs]
            [clj-http.client :as client]
            [com.puppetlabs.puppetdb.version]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [com.puppetlabs.puppetdb.cli.services :refer :all]
            [clojure.test :refer :all]
            [clj-time.core :refer [days hours minutes secs]]
            [clojure.java.io :refer [resource]]
            [com.puppetlabs.time :refer [to-secs to-minutes to-hours to-days period?]]))

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

(deftest whitelisting
  (testing "should log on reject"
    (let [wl (fs/temp-file)]
      (.deleteOnExit wl)
      (spit wl "foobar")
      (let [f (build-whitelist-authorizer (fs/absolute-path wl))]
        (is (true? (f {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (false? (f {:ssl-client-cn "badguy"})))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate whitelist " @logz)))))))))

(deftest url-prefix-test
  (let [vardir (fs/file "." "target" "var")]
    (fs/mkdirs vardir)
    (testing "should mount web app at `/` by default"
      (tk/with-app-with-config app
        [puppetdb-service
         jetty9/jetty9-service]
        {:jetty  {:port 8080}
         :global {:vardir vardir}}
        (let [response (client/get "http://localhost:8080/v4/version")]
          (is (= 200 (:status response))))))
    (testing "should support mounting web app at alternate url prefixL"
      (tk/with-app-with-config app
        [puppetdb-service
         jetty9/jetty9-service]
        {:jetty  {:port 8080}
         :global {:vardir     vardir
                  :url-prefix "/puppetdb"}}
        (let [response (client/get "http://localhost:8080/v4/version" {:throw-exceptions false})]
          (is (= 404 (:status response))))
        (let [response (client/get "http://localhost:8080/puppetdb/v4/version")]
          (is (= 200 (:status response))))))
    (testing "should support mounting web app at alternate url prefix"
      (tk/with-app-with-config app
         [puppetdb-service
          jetty9/jetty9-service]
         {:jetty  {:port 8080}
          :global {:vardir     vardir
                   :url-prefix "puppetdb"}}
         (let [response (client/get "http://localhost:8080/v4/version" {:throw-exceptions false})]
           (is (= 404 (:status response))))
         (let [response (client/get "http://localhost:8080/puppetdb/v4/version")]
           (is (= 200 (:status response))))))))