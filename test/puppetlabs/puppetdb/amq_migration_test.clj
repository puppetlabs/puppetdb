(ns puppetlabs.puppetdb.amq-migration-test
  (:import [java.nio.file Files]
           [org.apache.activemq.broker BrokerService])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.amq-migration :refer :all]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.examples :as ex]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.cli :as cli-utils]
            [puppetlabs.puppetdb.cli.services :refer [shared-globals]]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.puppetdb.testutils :as tutils]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.trapperkeeper.services :refer [service-context]]
            [puppetlabs.kitchensink.core :as ks]
            [metrics.counters :as mc]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.testutils.log :as tlog]
            [puppetlabs.puppetdb.metrics.core :refer [metrics-registries]]
            [me.raynes.fs :as fs]
            [metrics.meters :as meters]))

(defn enqueue-and-shutdown [config commands]
  (let [broker (build-and-start-broker! "localhost"
                                           (mq-dir config)
                                           (:command-processing config))
        endpoint (conf/mq-endpoint config)]

    (.setPersistent broker true)
    (try
      (start-broker! broker)

      (with-open [factory (activemq-connection-factory (conf/mq-broker-url config))
                  conn (doto (.createConnection factory) .start)]
        (doseq [[header command] commands]
          (mq/send-message! conn
                            endpoint
                            (json/generate-string command)
                            header)))
      (finally
        (stop-broker! broker)))))

(defn dlo-context [server]
  (-> (get-service server :PuppetDBServer)
      shared-globals
      :dlo))

(defn dlo-paths [server]
  (with-open [path-stream (-> server
                              dlo-context
                              :path
                              Files/newDirectoryStream)]
    (mapv (comp str #(.toAbsolutePath %))
          (-> path-stream .iterator iterator-seq))))

(defn processed-state []
  (reduce (fn [acc metric]
            (assoc acc
                   (keyword metric)
                   (->> ["global" metric]
                        (meters/meter (get-in metrics-registries [:mq :registry]))
                        meters/rates
                        :total)))
          {} ["processed" "retried" "discarded"]))

(defn await-processed [await-how-many starting-processed-state timeout-ms]
  (let [stop-time (+ (System/currentTimeMillis)
                     timeout-ms)
        {prev-processed :processed
         prev-retried :retried
         prev-discarded :discarded} starting-processed-state]
    (loop []
      (let [{:keys [processed retried discarded]} (processed-state)]
        (cond

          (> (System/currentTimeMillis) stop-time)
          (throw (Exception. "Timed out waiting for message to be processed"))

          (< prev-retried retried)
          (throw (Exception. "Unexepcted retry of message"))

          (< prev-discarded discarded)
          (throw (Exception. "Unexpected message discard"))

          (>= processed (+ await-how-many prev-processed))
          true

          :else
          (do
            (Thread/sleep 10)
            (recur)))))))

(deftest shovel-upgrade-test
  (testing "an upgrade with existing catalogs"
    (with-log-suppressed-unless-notable tlog/critical-errors
      (with-test-db
        (let [config (svc-utils/create-temp-config)
              defaulted-config (conf/process-config! (assoc config :database *db*))
              cat (get-in ex/wire-catalogs [9 :basic])
              cat2 {"command" "replace catalog"
                    "version" 4
                    "payload" (get-in ex/wire-catalogs [4 :empty])}]

          (is (not (needs-upgrade? config)))

          (enqueue-and-shutdown defaulted-config [[{"command" "replace catalog"
                                                    "certname" (:certname cat)
                                                    "version" "9"
                                                    "received" (ks/timestamp)
                                                    "id" (ks/uuid)}
                                                   cat]
                                                  [{"received" (ks/timestamp)
                                                    "id" (ks/uuid)}
                                                   cat2]])
          (is (needs-upgrade? config))

          (let [before-start-state (processed-state)]
            (svc-utils/call-with-puppetdb-instance
             (assoc config :database *db*)
             (fn []
               (await-processed 2 before-start-state tutils/default-timeout-ms)
               (is (not (needs-upgrade? config)))
               (let [results (cli-utils/get-catalogs "basic.wire-catalogs.com")]
                 (is (= 1 (count results)))
                 (is (= (:catalog_uuid cat)
                        (:catalog_uuid (first results)))))
               (let [results (cli-utils/get-catalogs "empty.wire-catalogs.com")]
                 (is (= 1 (count results)))
                 (is (= (:transaction_uuid cat)
                        (:transaction_uuid (first results))))))))))))

  (testing "an upgrade with a bad catalog"
    (with-log-suppressed-unless-notable (every-pred tlog/critical-errors
                                                    (tlog/starting-with "Output of coerce-to-new-command does not"))
      (with-test-db
        (let [config (svc-utils/create-temp-config)
              defaulted-config (conf/process-config! (assoc config :database *db*))
              cat (get-in ex/wire-catalogs [9 :basic])]

          (is (not (needs-upgrade? config)))

          (enqueue-and-shutdown defaulted-config [[{"received" (ks/timestamp)
                                                    "id" (ks/uuid)}
                                                   {"command" "replace catalog"
                                                    "version" "4"
                                                    "payload" (dissoc (get-in ex/wire-catalogs [4 :empty])
                                                                      :name :environment :version)}]
                                                  [{"command" "replace catalog"
                                                    "certname" (:certname cat)
                                                    "version" "9"
                                                    "received" (ks/timestamp)
                                                    "id" (ks/uuid)}
                                                   cat]])
          (is (needs-upgrade? config))

          (let [before-start-state (processed-state)]
            (svc-utils/call-with-puppetdb-instance
             (assoc config :database *db*)
             (fn []
               (await-processed 1 before-start-state tutils/default-timeout-ms)
               (is (= 2
                      (-> svc-utils/*server*
                          dlo-paths
                          count)))
               (is (not (needs-upgrade? config)))
               (let [results (cli-utils/get-catalogs (:certname cat))]
                 (is (= 1 (count results)))
                 (is (= (:catalog_uuid cat)
                        (:catalog_uuid (first results))))))))))))
  (testing "no upgrade needed"
    (with-log-suppressed-unless-notable tlog/critical-errors
      (with-test-db
        (let [config (svc-utils/create-temp-config)
              defaulted-config (conf/process-config! (assoc config :database *db*))
              mock-upgrade-fn (tutils/mock-fn)]

          (is (not (needs-upgrade? config)))

          (with-redefs [activemq->stockpile mock-upgrade-fn]
            (svc-utils/call-with-puppetdb-instance
             (assoc config :database *db*)
             (fn []
               (is (not @mock-upgrade-fn))
               (is (not (needs-upgrade? config)))))))))))

(deftest corrupt-kahadb-journal
  (testing "corrupt kahadb journal handling"
    (testing "corruption should return exception"
      ;; We are capturing the previous known failure here, just in case in the
      ;; future ActiveMQ changes behaviour (hopefully fixing this problem) so
      ;; we can make a decision about weither capturing EOFException and
      ;; restarting the broker ourselves is still needed.
      ;;
      ;; Upstream bug is: https://issues.apache.org/jira/browse/AMQ-4339
      (let [dir (kitchensink/absolute-path (fs/temp-dir "corrupt-kahadb-handling"))
            broker-name  "test"]
        (try
          ;; Start and stop a broker, then corrupt the journal
          (let [broker (build-embedded-broker broker-name dir {})]
            (start-broker! broker)
            (stop-broker! broker)
            (spit (fs/file dir "test" "KahaDB" "db-1.log") "asdf"))
          ;; Upon next open, we should get an EOFException
          (let [broker (build-embedded-broker broker-name dir {})]
            (is (thrown? java.io.EOFException (start-broker! broker))))
          ;; Now lets clean up
          (finally
            (fs/delete-dir dir)))))
    (testing "build-and-start-broker! should ignore the corruption"
      ;; Current work-around is to restart the broker upon this kind of
      ;; corruption. This test makes sure this continues to work for the
      ;; lifetime of this code.
      (let [dir (kitchensink/absolute-path (fs/temp-dir "ignore-kahadb-corruption"))
            broker-name "test"]
        (try
          ;; Start and stop a broker, then corrupt the journal
          (let [broker (build-embedded-broker broker-name dir {})]
            (start-broker! broker)
            (stop-broker! broker)
            (spit (fs/file dir "test" "KahaDB" "db-1.log") "asdf"))
          ;; Now lets use the more resilient build-and-start-broker!
          (let [broker (build-and-start-broker! broker-name dir {})]
            (stop-broker! broker))
          ;; Now lets clean up
          (finally
            (fs/delete-dir dir)))))))

(deftest test-build-broker
  (testing "build-embedded-broker"
    (try
      (let [broker (build-embedded-broker "localhost" "somedir" {})]
        (is (instance? BrokerService broker)))
      (let [size-megs 50
            size-bytes (* size-megs 1024 1024)

            broker (build-embedded-broker "localhost" "somedir"
                                          {:store-usage size-megs
                                           :temp-usage  size-megs
                                           :memory-usage size-megs})]
        (is (instance? BrokerService broker))
        (is (.. broker (getPersistenceAdapter) (isIgnoreMissingJournalfiles)))
        (is (.. broker (getPersistenceAdapter) (isArchiveCorruptedIndex)))
        (is (.. broker (getPersistenceAdapter) (isCheckForCorruptJournalFiles)))
        (is (.. broker (getPersistenceAdapter) (isChecksumJournalFiles)))
        (is (= size-bytes (.. broker (getSystemUsage) (getMemoryUsage) (getLimit))))
        (is (= size-bytes (.. broker (getSystemUsage) (getStoreUsage) (getLimit))))
        (is (= size-bytes (.. broker (getSystemUsage) (getTempUsage) (getLimit)))))
      (finally
        (fs/delete-dir "somedir")))))
