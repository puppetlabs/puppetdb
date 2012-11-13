(ns com.puppetlabs.puppetdb.test.http.v2.status
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.test.http.v1.status :as v1]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.examples.report :as examples]
            [com.puppetlabs.http :as pl-http])
  (:use [clojure.test]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.coerce :only [to-date-time]]
        [com.puppetlabs.utils :only (uuid)]))


(use-fixtures :each with-test-db with-http-app)

(def v2-url "/v2/status/nodes/")

;; All of the tests for v1 of the status endpoint are still valid, so we
;; just call them with our URL.
(deftest v1-node-status
  (v1/test-v1-node-status v2-url))

(deftest report-status
  (let [report      (:basic examples/reports)
        certname    (:certname report)
        timestamp   (now)]
    (scf-store/add-certname! certname)
    (scf-store/add-report! report timestamp)

    (testing "should have a last report time"
      (let [response (v1/get-response v2-url certname)
            status   (json/parse-string (:body response) true)]
        (is (= pl-http/status-ok (:status response)))

        (is (= certname (:name status)))
        (is (= (to-date-time (:end-time report))
               (to-date-time (:report_timestamp status))))))))

