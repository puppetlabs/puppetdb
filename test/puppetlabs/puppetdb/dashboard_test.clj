(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :refer :all]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.http.client.sync :as pl-http]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]
            [puppetlabs.puppetdb.middleware :as mid]))

(deftest dashboard-resource-requests
  (testing "dashboard redirect works"
    (let [handler (mid/make-pdb-handler dashboard-routes)
          {:keys [status headers]} (handler (request :get "/"))]
      (is (= status 302))
      (is (= "/pdb/dashboard/index.html" (get headers "Location"))))))

(deftest root-dashboard-routing
  (svc-utils/call-with-single-quiet-pdb-instance
   (fn []
     (let [root-resp (-> svc-utils/*base-url*
                         (assoc :prefix "/")
                         base-url->str-with-prefix
                         (pl-http/get {:as :text}))]
       (tu/assert-success! root-resp)
       (is (dtu/dashboard-page? root-resp))))))
