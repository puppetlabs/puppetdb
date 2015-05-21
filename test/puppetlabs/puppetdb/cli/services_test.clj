(ns puppetlabs.puppetdb.cli.services-test
  (:import [java.security KeyStore])
  (:require [fs.core :as fs]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.version]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [*base-url*]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :refer [block-until-results]]
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
        (is (= :authorized (f {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (string? (f {:ssl-client-cn "badguy"})))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate whitelist " @logz)))))))))

(deftest url-prefix-test
  (testing "should mount web app at `/` by default"
    (svc-utils/with-puppetdb-instance
      (let [url (str (utils/base-url->str *base-url*) "/version")
            response (client/get url)]
        (is (= 200 (:status response))))))
  (testing "should support mounting web app at alternate url prefix"
    (svc-utils/puppetdb-instance
     (assoc-in (svc-utils/create-config)
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
  (let [pdb-service (get-service svc-utils/*server* :PuppetDBServer)
        results (atom nil)
        before-slurp? (atom nil)
        after-slurp? (atom nil)]
    (query pdb-service endpoint version q pagination
           (fn [result-set]
             ;; We evaluate the first element from lazy-seq just to check if DB query was successful or not
             ;; so we have to ensure the first element and the rest have been realized, not just the first
             ;; element on its own.
             (reset! before-slurp? (realized? result-set))
             (reset! results (into [] result-set))
             (reset! after-slurp? (realized? result-set))))
    (is (false? @before-slurp?))
    (check-result @results)
    (is (true? @after-slurp?))))

(deftest query-via-puppdbserver-service
  (svc-utils/with-puppetdb-instance
    (let [pdb-service (get-service svc-utils/*server* :PuppetDBServer)]
      (submit-command pdb-service :replace-facts 4 {:certname "foo.local"
                                                    :environment "DEV"
                                                    :values {:foo "the foo"
                                                             :bar "the bar"
                                                             :baz "the baz"}
                                                    :producer_timestamp (to-string (now))})

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
                (set result))))))))

(deftest pagination-via-puppdbserver-service
  (svc-utils/with-puppetdb-instance
    (let [pdb-service (get-service svc-utils/*server* :PuppetDBServer)]
      (submit-command pdb-service :replace-facts 4 {:certname "foo.local"
                                                    :environment "DEV"
                                                    :values {:a "a" :b "b" :c "c"}
                                                    :producer_timestamp (to-string (now))})
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
             {:order_by [[:name order]]
              :offset offset
              :limit limit}
             (fn [result]
               (is (= (map #(hash-map :name % :value %
                                      :environment "DEV",
                                      :certname "foo.local")
                           expected)
                      result))))))))))

(deftest api-retirements
  (svc-utils/with-puppetdb-instance
    (letfn [(ping [v]
              (client/get
               (str (utils/base-url->str (assoc *base-url* :version v))
                    "/version")
               {:throw-exceptions false}))
            (retirement-response? [v response]
              (and (= 404 (:status response))
                   (= (format "The %s API has been retired; please use v4"
                              (name v))
                      (:body  response))))]
      (is (= 200 (:status (ping :v4))))
      (doseq [v [:v1 :v2 :v3]]
        (testing (format "%s requests are refused" (name v)))
        (is (retirement-response? v (ping v)))))))

(deftest in-process-command-submission
  (svc-utils/with-puppetdb-instance
    (let [pdb-service (get-service svc-utils/*server* :PuppetDBServer)]
      (submit-command pdb-service :replace-facts 4 {:certname "foo.local"
                                                    :environment "DEV"
                                                    :values {:foo "the foo"
                                                             :bar "the bar"
                                                             :baz "the baz"}
                                                    :producer_timestamp (to-string (now))})
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
                (set result))))))))
