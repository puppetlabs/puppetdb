(ns com.puppetlabs.puppetdb.test.http.v3.catalogs
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat])
  (:use  [clojure.java.io :only [resource]]
         clojure.test
         ring.mock.request
         [com.puppetlabs.puppetdb.testutils :only [get-request]]
         [com.puppetlabs.puppetdb.fixtures]))

(def endpoint "/v3/catalogs")

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str endpoint "/" node)))))

(deftest catalog-retrieval
  (let [original-catalog-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/big-catalog.json"))
        original-catalog     (json/parse-string original-catalog-str)
        certname             (get-in original-catalog ["data" "name"])
        catalog-version      (str (get-in original-catalog ["data" "version"]))]
    (testcat/replace-catalog original-catalog-str)
    (testing "it should return the catalog if it's present"
      (let [{:keys [status body] :as response} (get-response certname)]
        (is (= status 200))
        (is (= (testcat/munge-catalog-for-comparison original-catalog)
               (testcat/munge-catalog-for-comparison (json/parse-string body))))))))
