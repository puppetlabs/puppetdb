(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :as dashboard]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clojure.java.io :refer [file]]))

(deftest dashboard-resource-requests
  (testing "serving the dashboard works correctly"
    (let [{:keys [status body]} (dashboard/dashboard (request :get "/dashboard/index.html"))]
      (is (= status http/status-ok))
      (is (instance? java.io.File body))
      (is (= (file (System/getProperty "user.dir")
                   "resources/public/dashboard/index.html") body))))

  (testing "dashboard redirect works"
    (let [{:keys [status headers]} (dashboard/dashboard-redirect (request :get "/"))]
      (is (= status 302))
      (is (= "/pdb/dashboard/index.html" (get headers "Location"))))))
