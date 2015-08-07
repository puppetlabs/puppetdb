(ns puppetlabs.puppetdb.dashboard-test
  (:require [puppetlabs.puppetdb.dashboard :as dashboard]
            [puppetlabs.puppetdb.http :as http]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clojure.java.io :refer [file]]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]))

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

(defn base-url->str'
  "Similar to puppetlabs.puppetdb.utils/base-url->str but doesn't
  include a version as the dashboard page does not include a version"
  [{:keys [protocol host port prefix] :as base-url}]
  (-> (java.net.URL. protocol host port prefix)
      .toURI .toASCIIString))

(defn dashboard-page? [{:keys [body] :as req}]
  (.contains body "<title>PuppetDB: Dashboard</title>"))

(deftest dashboard-routing
  (svc-utils/puppetdb-instance
   (fn []
     (let [pdb-resp (client/get (base-url->str' (assoc svc-utils/*base-url*
                                                  :prefix "/pdb")))
           root-resp (client/get (base-url->str' (assoc svc-utils/*base-url*
                                                   :prefix "/")))]
       (tu/assert-success! pdb-resp)
       (tu/assert-success! root-resp)

       (is (dashboard-page? pdb-resp))
       (is (dashboard-page? root-resp))

       (is (= (:body pdb-resp)
              (:body root-resp)))))))
