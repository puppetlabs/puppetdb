(ns puppetlabs.puppetdb.cli.services-test
  (:import [java.security KeyStore])
  (:require [me.raynes.fs :as fs]
            [clj-http.client :as client]
            [puppetlabs.http.client.sync :as pl-http]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output logs-matching]]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.testutils.cli :refer [get-factsets]]
            [puppetlabs.puppetdb.command :refer [enqueue-command]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.meta.version :as version]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils :refer [*base-url*]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.testutils :refer [block-until-results temp-file]]
            [clj-time.coerce :refer [to-string]]
            [clj-time.core :refer [now]]))

(deftest update-checking
  (let [config-map {:global {:product-name "puppetdb"
                              :update-server "update-server!"}}]

    (testing "should check for updates if running as puppetdb"
      (with-redefs [version/check-for-updates! (constantly "Checked for updates!")]
        (is (= (maybe-check-for-updates config-map {}) "Checked for updates!"))))

    (testing "should skip the update check if running as pe-puppetdb"
      (with-log-output log-output
        (maybe-check-for-updates (assoc-in config-map [:global :product-name] "pe-puppetdb") {})
        (is (= 1 (count (logs-matching #"Skipping update check on Puppet Enterprise" @log-output))))))))

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
             (reset! before-slurp? (and (realized? result-set) (realized? (rest result-set))))
             (reset! results (vec result-set))
             (reset! after-slurp? (and (realized? result-set) (realized? (rest result-set))))))
    (is (false? @before-slurp?))
    (check-result @results)
    (is (true? @after-slurp?))))

(deftest query-via-puppdbserver-service
  (svc-utils/with-single-quiet-pdb-instance
    (let [dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          query-fn (partial query (get-service svc-utils/*server* :PuppetDBServer))]
      (enqueue-command dispatcher :replace-facts 4
                       {:certname "foo.local"
                        :environment "DEV"
                        :values {:foo "the foo"
                                 :bar "the bar"
                                 :baz "the baz"}
                        :producer_timestamp (to-string (now))})

      @(block-until-results 100 (first (get-factsets "foo.local")))

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
    (let [dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          query-fn (partial query (get-service svc-utils/*server* :PuppetDBServer))]
      (enqueue-command dispatcher :replace-facts 4
                       {:certname "foo.local"
                        :environment "DEV"
                        :values {:a "a" :b "b" :c "c"}
                        :producer_timestamp (to-string (now))})

      @(block-until-results 100 (first (get-factsets "foo.local")))
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
                    "/facts")
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

(defn make-https-request-with-whitelisted-host [whitelisted-host]
  (let [whitelist-file (temp-file "whitelist-log-reject")
        cert-config {:ssl-cert "test-resources/localhost.pem"
                     :ssl-key "test-resources/localhost.key"
                     :ssl-ca-cert "test-resources/ca.pem"}
        config (-> (svc-utils/create-temp-config)
                   (assoc :jetty (merge cert-config
                                        {:ssl-port 0
                                         :ssl-host "0.0.0.0"
                                         :ssl-protocols "TLSv1,TLSv1.1"}))
                   (assoc-in [:puppetdb :certificate-whitelist] (str whitelist-file)))]
    (spit whitelist-file whitelisted-host)

    (svc-utils/call-with-puppetdb-instance
     config
     (fn []
       (pl-http/get (str (utils/base-url->str (assoc *base-url* :version :v4))
                         "/facts")
                    (merge cert-config
                           {:headers {"accept" "application/json"}
                            :as :text}))))))

(deftest cert-whitelists
  (testing "hosts not in whitelist should be forbidden"
    (let [response (make-https-request-with-whitelisted-host "bogus")]
      (is (= 403 (:status response)))
      (is (re-find #"Permission denied" (:body response)))))

  (testing "host in the cert whitelist is allowed"
    (let [response (make-https-request-with-whitelisted-host "localhost")]
      (is (= 200 (:status response)))
      (is (not (re-find #"Permission denied" (:body response)))))))
