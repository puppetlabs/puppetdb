(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :refer :all]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clojure.java.io :refer [file]]
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

(deftest dashboard-routing
  (svc-utils/call-with-single-quiet-pdb-instance
   (fn []
     (let [root-resp (svc-utils/get-unparsed (svc-utils/root-url-str))]
       (tu/assert-success! root-resp)
       (is (dtu/dashboard-page? root-resp)))

     (let [data-resp (-> svc-utils/*base-url*
                         (assoc :prefix "/pdb/dashboard/data")
                         base-url->str-with-prefix
                         svc-utils/get-unparsed)]
       (tu/assert-success! data-resp)
       (is (http/json-utf8-ctype? (get-in data-resp [:headers "content-type"]) ))
       (is (seq? (json/parse-string (:body data-resp) true)))))))
