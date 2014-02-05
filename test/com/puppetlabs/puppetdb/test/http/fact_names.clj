(ns com.puppetlabs.puppetdb.test.http.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.fixtures :as fixt])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [paged-results get-request]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def versions [:v2 :v3 :v4])

(def route-suffix "fact-names")

(defn create-route [version]
  (str "/" (name version) "/" route-suffix))

(def routes (map create-route versions))

(fixt/defixture super-fixture :each fixt/with-test-db fixt/with-http-app)

(deftest fact-names-queries
  (testing "should not support paging-related query parameters"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [request (get-request (create-route :v2) nil {k v})
            {:keys [status body]} (fixt/*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))

(deftest fact-names-endpoint-tests
  (doseq [route routes]
    (super-fixture
     (fn []
       (testing (str "fact-names queries for " route ":")
         (let [facts1 {"domain" "testing.com"
                       "hostname" "foo1"
                       "kernel" "Linux"
                       "operatingsystem" "Debian"
                       "uptime_seconds" "4000"}
               facts2 {"domain" "testing.com"
                       "hostname" "foo2"
                       "kernel" "Linux"
                       "operatingsystem" "RedHat"
                       "uptime_seconds" "6000"}
               facts3 {"domain" "testing.com"
                       "hostname" "foo3"
                       "kernel" "Darwin"
                       "operatingsystem" "Darwin"
                       "memorysize" "16.00 GB"}]
           (testing "should return an empty list if there are no facts"
             (let [request (get-request route)
                   {:keys [status body]} (fixt/*app* request)
                   result (json/parse-string body)]
               (is (= status pl-http/status-ok))
               (is (empty? result))))

           (with-transacted-connection fixt/*db*
             (scf-store/add-certname! "foo1")
             (scf-store/add-certname! "foo2")
             (scf-store/add-certname! "foo3")
             (scf-store/add-facts! "foo2" facts2 (now))
             (scf-store/add-facts! "foo3" facts3 (now))
             (scf-store/deactivate-node! "foo1")
             (scf-store/add-facts! "foo1" facts1 (now)))

           (testing "should retrieve all fact names, order alphabetically, including deactivated nodes"
             (let [request (get-request route)
                   {:keys [status body]} (fixt/*app* request)
                   result (json/parse-string body)]
               (is (= status pl-http/status-ok))
               (is (= result ["domain" "hostname" "kernel" "memorysize" "operatingsystem" "uptime_seconds"]))))))))))
