(ns puppetlabs.pe-puppetdb-extensions.routing-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance]]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]))

;;; Tests to make sure routing isn't broken when running under PE

(deftest exports-work-from-pe
  (with-log-suppressed-unless-notable notable-pdb-event?
    (with-puppetdb-instance (utils/pdb1-sync-config)
      (let [admin-endpoint (assoc svcs/*base-url*
                                  :prefix "/pdb/admin"
                                  :version :v1)
            response (utils/get-response admin-endpoint "/archive" {:throw-exceptions false
                                                                    :accept :octet-stream})]
        (is (= 200 (:status response)))))))
