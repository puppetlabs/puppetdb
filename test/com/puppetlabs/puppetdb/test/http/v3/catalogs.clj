(ns com.puppetlabs.puppetdb.test.http.v3.catalogs
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [com.puppetlabs.puppetdb.catalogs :as cats]
            [clojure.walk :as walk])
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
        original-catalog     (json/parse-string original-catalog-str true)
        certname             (:name original-catalog)
        catalog-version      (:version original-catalog)]
    (testcat/replace-catalog original-catalog-str)
    (testing "it should return the catalog if it's present"
      (let [{:keys [status body] :as response} (get-response certname)]
        (is (= status 200))
        (is (= (testcat/munged-canonical->wire-format :v3 original-catalog)
               (testcat/munged-canonical->wire-format :v3 (update-in (cats/parse-catalog body 3) [:resources] vals))))))))
