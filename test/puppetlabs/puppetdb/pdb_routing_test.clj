(ns puppetlabs.puppetdb.pdb-routing-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.client :as pdb-client]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.cli.services :as clisvc]
            [puppetlabs.puppetdb.pdb-routing :refer :all]
            [puppetlabs.puppetdb.time :refer [now]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.http.client.sync :as http]))

(defn submit-facts [base-url certname facts]
  (svc-utils/sync-command-post base-url certname "replace facts" 4 facts))

(defn query-fact-names [{:keys [host port]}]
  (svc-utils/get (svc-utils/query-url-str "/fact-names")))

(defn export [{:keys [host port]}]
  (svc-utils/get (svc-utils/admin-url-str "/archive")))

(defn query-server-time [{:keys [host port]}]
  (svc-utils/get (svc-utils/meta-url-str "/server-time")))

(def test-facts {:certname "foo.com"
                 :environment "DEV"
                 :producer_timestamp (to-string (now))
                 :values {:foo 1
                          :bar "2"
                          :baz 3}})

(deftest top-level-routes
  (svc-utils/with-puppetdb-instance
    (let [pdb-resp (http/get (svc-utils/root-url-str) {:as :text})]
      (tu/assert-success! pdb-resp)
      (is (dtu/dashboard-page? pdb-resp))

      (is (-> (query-server-time svc-utils/*base-url*)
              (get-in [:body :server_time])
              time/parse-wire-datetime))

      (let [resp (export svc-utils/*base-url*)]
        (tu/assert-success! resp)
        (is (.contains (get-in resp [:headers "content-disposition"]) "puppetdb-export"))
        (is (:body resp))))))

(deftest maintenance-mode
  (svc-utils/with-puppetdb-instance
    (let [maint-mode-service (tk-app/get-service svc-utils/*server* :MaintenanceMode)]
      (is (= 200 (:status (submit-facts (svc-utils/pdb-cmd-url) "foo.com" test-facts))))
      (is (= #{"foo" "bar" "baz"}
             (-> (query-fact-names svc-utils/*base-url*)
                 :body
                 set)))
      (enable-maint-mode maint-mode-service)
      (is (= (:status (query-fact-names svc-utils/*base-url*))
             503))

      (disable-maint-mode maint-mode-service)
      (is (= #{"foo" "bar" "baz"}
             (-> (query-fact-names svc-utils/*base-url*)
                 :body
                 set))))))
