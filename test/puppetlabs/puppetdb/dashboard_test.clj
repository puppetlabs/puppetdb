(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :refer :all]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clojure.java.io :refer [file]]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.cheshire :as json]))

(deftest dashboard-resource-requests
  (testing "dashboard redirect works"
    (let [handler (mid/make-pdb-handler dashboard-routes)
          {:keys [status headers]} (handler (request :get "/"))]
      (is (= status 302))
      (is (= "/pdb/dashboard/index.html" (get headers "Location")))))
  (testing "dashboard data request works"
    (let [handler (build-app default-meter-defs)
          {:keys [status headers body]} (handler (request :get "/data"))
          body (json/parse-string body true)]
      (is (= status 200))
      (is (seq? body))
      (is (= (count body)
             (->> body (map :id) distinct count))))))

(deftest root-dashboard-routing
  (svc-utils/call-with-single-quiet-pdb-instance
   (fn []
     (let [root-resp (-> svc-utils/*base-url*
                         (assoc :prefix "/")
                         base-url->str-with-prefix
                         client/get)]
       (tu/assert-success! root-resp)
       (is (dtu/dashboard-page? root-resp))))))
