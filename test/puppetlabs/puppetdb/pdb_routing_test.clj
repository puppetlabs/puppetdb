(ns puppetlabs.puppetdb.pdb-routing-test
  (:require [clojure.test :refer :all]
            [clj-http.client :as client]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.client :as pdb-client]
            [clj-time.coerce :refer [to-string]]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils.dashboard :as dtu]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.cli.services :as clisvc]
            [puppetlabs.puppetdb.pdb-routing :refer :all]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils.http :as tuhttp]))

(defn submit-facts [base-url facts]
  (svc-utils/sync-command-post base-url "replace facts" 4 facts))

(defn query-fact-names [{:keys [host port]}]
  (tuhttp/pdb-get (utils/pdb-query-base-url host port :v4)
           "/fact-names"))

(defn export [{:keys [host port]}]
  (tuhttp/pdb-get (utils/pdb-admin-base-url host port :v1)
           "/archive"))

(defn query-server-time [{:keys [host port]}]
  (tuhttp/pdb-get (utils/pdb-meta-base-url host port :v1)
           "/server-time"))

(def test-facts {:certname "foo.com"
                 :environment "DEV"
                 :producer_timestamp (to-string (now))
                 :values {:foo 1
                          :bar "2"
                          :baz 3}})

(deftest top-level-routes
  (svc-utils/call-with-puppetdb-instance
   (fn []
     (let [pdb-resp (client/get (dtu/dashboard-base-url->str (assoc svc-utils/*base-url*
                                                                    :prefix "/pdb")))]
       (tu/assert-success! pdb-resp)
       (is (dtu/dashboard-page? pdb-resp))

       (is (-> (query-server-time svc-utils/*base-url*)
               (get-in [:body :server_time])
               time/from-string))

       (let [resp (export svc-utils/*base-url*)]
         (tu/assert-success! resp)
         (is (.contains (get-in resp [:headers "Content-Disposition"]) "puppetdb-export"))
         (is (:body resp)))))))

(deftest maintenance-mode
  (svc-utils/call-with-puppetdb-instance
   (fn []
     (let [maint-mode-service (tk-app/get-service svc-utils/*server* :MaintenanceMode)]
       (is (= 200 (:status (submit-facts (svc-utils/pdb-cmd-url) test-facts))))
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
                  set)))))))
