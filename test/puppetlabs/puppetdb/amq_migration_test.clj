(ns puppetlabs.puppetdb.amq-migration-test
  (:import [java.nio.file Files])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.amq-migration :refer :all]
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
            [puppetlabs.puppetdb.testutils.log :as tlog]))

(defn enqueue-and-shutdown [config commands]
  (let [broker (mq/build-and-start-broker! "localhost"
                                           (mq-dir config)
                                           (:command-processing config))
        endpoint (conf/mq-endpoint config)]

    (.setPersistent broker true)
    (try
      (mq/start-broker! broker)

      (with-open [factory (mq/activemq-connection-factory (conf/mq-broker-url config))
                  conn (doto (.createConnection factory) .start)]
        (doseq [[header command] commands]
          (mq/send-message! conn
                            endpoint
                            (json/generate-string command)
                            header)))
      (finally
        (mq/stop-broker! broker)))))

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

          (svc-utils/call-with-puppetdb-instance
           (assoc config :database *db*)
           (fn []
             (is (not (needs-upgrade? config)))
             (let [results (cli-utils/get-catalogs "basic.wire-catalogs.com")]
               (is (= 1 (count results)))
               (is (= (:catalog_uuid cat)
                      (:catalog_uuid (first results)))))
             (let [results (cli-utils/get-catalogs "empty.wire-catalogs.com")]
               (is (= 1 (count results)))
               (is (= (:transaction_uuid cat)
                      (:transaction_uuid (first results)))))))))))

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
          (svc-utils/call-with-puppetdb-instance
           (assoc config :database *db*)
           (fn []
             (is (= 2
                    (-> svc-utils/*server*
                        dlo-paths
                        count)))
             (is (not (needs-upgrade? config)))
             (let [results (cli-utils/get-catalogs (:certname cat))]
               (is (= 1 (count results)))
               (is (= (:catalog_uuid cat)
                      (:catalog_uuid (first results)))))))))))
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
