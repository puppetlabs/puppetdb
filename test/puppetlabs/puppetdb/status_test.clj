(ns puppetlabs.puppetdb.status-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(deftest info-level-status
  (svc-utils/with-puppetdb-instance
    (let [{:keys [body] :as pdb-resp} (-> svc-utils/*base-url*
                                          (assoc :prefix "/status/v1/services")
                                          base-url->str-with-prefix
                                          client/get)
          pdb-status (:puppetdb-status (json/parse-string body true))]
      (tu/assert-success! pdb-resp)
      (is (= "running" (:state pdb-status)))
      (is (= {:maintenance_mode? false
              :read_db_up? true
              :write_db_up? true
              :write_dbs_up? true
              :write_db {:default {:up? true}}
              :queue_depth 0}
             (:status pdb-status))))))

(deftest critical-level-status
  (svc-utils/with-puppetdb-instance
    (let [{:keys [body] :as pdb-resp} (-> svc-utils/*base-url*
                                          (assoc :prefix "/status/v1/services?level=critical")
                                          base-url->str-with-prefix
                                          client/get)
          pdb-status (:puppetdb-status (json/parse-string body true))]
      (tu/assert-success! pdb-resp)
      (is (= "running" (:state pdb-status)))
      (is (= nil (:status pdb-status))))))

(deftest maintenance-mode-status
  (with-redefs [puppetlabs.puppetdb.pdb-routing/maint-mode? (constantly true)]
    (svc-utils/with-puppetdb-instance
      (let [{:keys [body status]} (-> svc-utils/*base-url*
                                      (assoc :prefix "/status/v1/services")
                                      base-url->str-with-prefix
                                      (client/get {:throw-exceptions false}))
            pdb-status (:puppetdb-status (json/parse-string body true))]
        (is (= 503 status))
        (is (= "starting" (:state pdb-status)))
        (is (= {:maintenance_mode? true
                :read_db_up? true
                :write_db_up? true
                :write_dbs_up? true
                :write_db {:default {:up? true}}
                :queue_depth 0}
               (:status pdb-status)))))))

(deftest status-when-databases-down
  ;; FIXME: better test
  (svc-utils/with-puppetdb-instance
    (with-redefs [sutils/db-up? (constantly false)]
      (let [{:keys [body status]} (-> svc-utils/*base-url*
                                      (assoc :prefix "/status/v1/services")
                                      base-url->str-with-prefix
                                      (client/get {:throw-exceptions false}))
            pdb-status (:puppetdb-status (json/parse-string body true))]
        (is (= 503 status))
        (is (= "error" (:state pdb-status)))
        (is (= {:maintenance_mode? false
                :read_db_up? false
                :write_db_up? false
                :write_dbs_up? false
                :write_db {:default {:up? false}}
                :queue_depth 0}
               (:status pdb-status)))))))
