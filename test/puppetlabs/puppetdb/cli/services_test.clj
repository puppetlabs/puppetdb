(ns puppetlabs.puppetdb.cli.services-test
  (:import [java.security KeyStore])
  (:require clojure.string
            [fs.core :as fs]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.version]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk]
            [puppetlabs.trapperkeeper.services.webserver.jetty9-service :as jetty9]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.test :refer :all]
            [clj-time.core :refer [days hours minutes secs]]
            [clojure.java.io :refer [resource]]
            [puppetlabs.puppetdb.time :refer [to-secs to-minutes to-hours to-days period?]]
            [puppetlabs.puppetdb.testutils.jetty :as jutils :refer [*base-url*]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :refer [block-on-node
                                                                          submit-command
                                                                          block-until-results]]
            [clj-time.coerce :refer [to-string]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.cli.export :as export :refer [facts-for-node]]))

(deftest update-checking
  (testing "should check for updates if running as puppetdb"
    (with-redefs [puppetlabs.puppetdb.version/update-info (constantly {:version "0.0.0" :newer true})]
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
  (testing "should mount web app at `/` by default"
    (jutils/with-puppetdb-instance
      (let [url (str (utils/base-url->str *base-url*) "/version")
            response (client/get url)]
        (is (= 200 (:status response))))))
  (testing "should support mounting web app at alternate url prefix"
    (jutils/puppetdb-instance
     (assoc-in (jutils/create-config)
               [:web-router-service :puppetlabs.puppetdb.cli.services/puppetdb-service]
               "/puppetdb")
     (fn []
       (let [url (str (utils/base-url->str (dissoc *base-url* :prefix))
                      "/version")
             response (client/get url {:throw-exceptions false})]
         (is (= 404 (:status response))))
       (let [url (str (utils/base-url->str *base-url*) "/version")
             response (client/get url)]
         (is (= 200 (:status response))))))))

(defn- check-service-query
  [endpoint version q pagination check-result]
  (let [pdb-service (get-service jutils/*server* :PuppetDBServer)
        results (atom nil)
        before-slurp? (atom nil)
        after-slurp? (atom nil)]
    (query pdb-service endpoint version q pagination
           (fn [f]
             (f
              (fn [result-set]
                (reset! before-slurp? (realized? result-set))
                (reset! results result-set)
                (reset! after-slurp? (realized? result-set))))))
    (is (false? @before-slurp?))
    (check-result @results)
    (is (false? @after-slurp?))))

(deftest query-via-puppdbserver-service
  (jutils/with-puppetdb-instance
    (submit-command *base-url*
                    :replace-facts 3 {:name "foo.local"
                                      :environment "DEV"
                                      :values {:foo "the foo"
                                               :bar "the bar"
                                               :baz "the baz"}
                                      :producer-timestamp (to-string (now))})
    @(block-until-results 100 (facts-for-node *base-url* "foo.local"))
    (check-service-query
     :facts :v4 ["=" "certname" "foo.local"]
     nil
     (fn [result]
       (is (= #{{:value "the baz",
               :name "baz",
                 :environment "DEV",
                 :certname "foo.local"}
                {:value "the bar",
                 :name "bar",
                 :environment "DEV",
                 :certname "foo.local"}
                {:value "the foo",
                 :name "foo",
                 :environment "DEV",
                 :certname "foo.local"}}
              (set result)))))))

(deftest pagination-via-puppdbserver-service
  (jutils/with-puppetdb-instance
    (submit-command *base-url*
                    :replace-facts 3 {:name "foo.local"
                                      :environment "DEV"
                                      :values {:a "a" :b "b" :c "c"}
                                      :producer-timestamp (to-string (now))})
    @(block-until-results 100 (facts-for-node *base-url* "foo.local"))
    (let [exp ["a" "b" "c"]
          rexp (reverse exp)]
      (doseq [order [:ascending :descending]
              offset (range (dec (count exp)))
              limit (range 1 (count exp))]
        (let [expected (take limit
                             (drop offset (if (= order :ascending) exp rexp)))]
          (check-service-query
           :facts :v4 ["=" "certname" "foo.local"]
           {:order-by [[:name order]]
            :offset offset
            :limit limit}
           (fn [result]
             (is (= (map #(hash-map :name % :value %
                                    :environment "DEV",
                                    :certname "foo.local")
                         expected)
                    result)))))))))
