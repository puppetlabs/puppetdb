(ns com.puppetlabs.puppetdb.test.http.experimental.catalog
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalog :as testcat])
  (:use  [clojure.java.io :only [resource]]
         clojure.test
         ring.mock.request
         [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db with-http-app)


(def c-t "application/json")

(defn get-request
  [path]
  (let [request (request :get path)]
    (update-in request [:headers] assoc "Accept" c-t)))

(defn get-response
  ([]      (get-response nil))
  ([node] (*app* (get-request (str "/experimental/catalog/" node)))))


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
