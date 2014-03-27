(ns com.puppetlabs.puppetdb.test.http.v4.catalogs
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat])
  (:use  [clojure.java.io :only [resource]]
         clojure.test
         ring.mock.request
         [com.puppetlabs.puppetdb.testutils :only [get-request]]
         [com.puppetlabs.puppetdb.fixtures]))

(def endpoint "/v4/catalogs")

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str endpoint "/" node)))))

(deftest catalog-retrieval
  (let [original-catalog-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/big-catalog.json"))
        original-catalog     (json/parse-string original-catalog-str)
        certname             (get original-catalog "name")
        catalog-version      (get original-catalog "version")]
    (testcat/replace-catalog original-catalog-str)
    (testing "it should return the catalog if it's present"
      (let [{:keys [status body] :as response} (get-response certname)
            result (json/parse-string body)]
        (is (= status 200))
        
        (is (string? (get result "environment")))
        (is (= (get original-catalog "environment")
               (get result "environment")))
        (is (= (testcat/munge-catalog-for-comparison :v4 original-catalog)
               (testcat/munge-catalog-for-comparison :v4 result)))))))
