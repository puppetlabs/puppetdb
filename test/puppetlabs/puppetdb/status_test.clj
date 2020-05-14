(ns puppetlabs.puppetdb.status-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]
            [puppetlabs.puppetdb.cheshire :as json]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(deftest status-test
  (testing "status returns as expected on normal operation"
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

  (testing "status returns as expected when in maintenance mode"
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
                 (:status pdb-status))))))))
