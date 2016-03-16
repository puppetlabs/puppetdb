(ns puppetlabs.pe-puppetdb-extensions.routing-test
  (:require [clojure.test :refer :all]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [sync-config with-ext-instances]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]))

;;; Tests to make sure routing isn't broken when running under PE

(deftest exports-work-from-pe
  (with-ext-instances [pdb1 (sync-config nil)]
    (let [admin-endpoint (assoc svcs/*base-url*
                                :prefix "/pdb/admin"
                                :version :v1)
          response (utils/get-response admin-endpoint "/archive"
                                       {:throw-exceptions false
                                        :accept :octet-stream})]
      (is (= 200 (:status response))))))

(deftest status-service
  (with-ext-instances [pdb1 (sync-config nil)]
    (let [status-endpoint (assoc svcs/*base-url*
                                 :prefix "/status"
                                 :version :v1)
          body (-> status-endpoint
                   (utils/get-response "/services" {})
                   :body
                   (json/parse-string true))]
      (is (= "running" (-> body :puppetdb-status :state))))))
