(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :as dashboard]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clojure.java.io :refer [file]]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]))

(deftest dashboard-resource-requests
  (testing "dashboard redirect works"
    (let [{:keys [status headers]} (dashboard/dashboard-redirect (request :get "/"))]
      (is (= status 302))
      (is (= "/pdb/dashboard/index.html" (get headers "Location"))))))

(deftest root-dashboard-routing
  (svc-utils/call-with-single-quiet-pdb-instance
   (fn []
     (let [root-resp (-> svc-utils/*base-url*
                         (assoc :prefix "/")
                         base-url->str-with-prefix
                         client/get)]
       (tu/assert-success! root-resp)
       (is (dtu/dashboard-page? root-resp))))))
