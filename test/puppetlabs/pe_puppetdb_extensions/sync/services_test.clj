(ns puppetlabs.pe-puppetdb-extensions.sync.services-test
  (:import [org.joda.time Period]
           [java.net URI])
  (:require [puppetlabs.pe-puppetdb-extensions.sync.services :refer :all]
            [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clj-time.core :refer [seconds]]
            [puppetlabs.puppetdb.time :refer [period? periods-equal? parse-period]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as ks]))

(deftest enable-periodic-sync?-test
  (testing "Happy case"
    (is (= true (enable-periodic-sync?
                 [{:server-url (URI. "http://foo.bar:8080"), :interval (-> 12 seconds)}]))))

  (testing "Disable sync cases"
    (are [remote-config] (= false (enable-periodic-sync? remote-config))
      [{:server-url (URI. "http://foo.bar:8080"), :interval (-> 0 seconds)}]
      [{:server-url (URI. "http://foo.bar:8080")}]
      []
      nil))

  (testing "Invalid sync configs"
    (are [remote-config] (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                                   (enable-periodic-sync? remote-config))

      [{:server_url "http://foo.bar:8080", :interval (-> -12 seconds)}])))

(deftest validate-trigger-sync-test
  (let [allow-unsafe-sync-triggers false
        jetty-config {}
        remotes-config [{:server-url (URI. "http://foo.bar:8080"), :interval (parse-period "120s")}]]
    (is (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config {:url "http://foo.bar:8080/pdb/query/v4"}))
    (is (not (validate-trigger-sync allow-unsafe-sync-triggers remotes-config jetty-config {:url "http://baz.buzz:8080/pdb/query/v4"})))))

(deftest test-wait-for-sync
  (testing "Happy path of processing commands"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (async/>!! processed-commands-chan {:id cmd-1})
      (is (= :done (async/<!! finished-sync)))))

  (testing "Receiving a processed command before submitted commands channel is closed"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/>!! processed-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (is (= :done (async/<!! finished-sync)))))

  (testing "timeout result when processing of commands is too slow"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 500)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! submitted-commands-chan)
      (is (= :timed-out (async/<!! finished-sync)))))

  (testing "system shutting down during initial sync"
    (let [submitted-commands-chan (async/chan)
          processed-commands-chan (async/chan 1)
          finished-sync (wait-for-sync submitted-commands-chan processed-commands-chan 15000)
          cmd-1 (ks/uuid)]
      (async/>!! submitted-commands-chan {:id cmd-1})
      (async/close! processed-commands-chan)
      (is (= :shutting-down (async/<!! finished-sync))))))
