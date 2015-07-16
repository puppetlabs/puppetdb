(ns com.puppetlabs.puppetdb.test.http.catalogs
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [com.puppetlabs.puppetdb.catalogs :as cats]
            [clojure.java.io :refer [resource]]
            [clojure.test :refer :all]
            [ring.mock.request :as request]
            [com.puppetlabs.puppetdb.testutils :refer [get-request deftestseq]]
            [com.puppetlabs.puppetdb.fixtures :as fixt]))

(def endpoints [[:v3 "/v3/catalogs"]
                [:v4 "/v4/catalogs"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def c-t "application/json")

(defn get-response
  ([endpoint]
   (get-response endpoint nil))
  ([endpoint node]
   (fixt/*app* (get-request (str endpoint "/" node)))))

(deftestseq catalog-retrieval
  [[version endpoint] endpoints
   :let [original-catalog-str (slurp (resource "com/puppetlabs/puppetdb/test/cli/export/big-catalog.json"))
         original-catalog     (json/parse-string original-catalog-str true)
         certname             (:name original-catalog)
         catalog-version      (:version original-catalog)]]

  (testcat/replace-catalog original-catalog-str)
  (testing "it should return the catalog if it's present"
    (let [{:keys [status body] :as response} (get-response endpoint certname)
          result (json/parse-string body)]
      (is (= status 200))

      (when (not= version :v3)
        (is (string? (get result "environment")))
        (is (= (get original-catalog :environment)
               (get result "environment"))))

      (let [original (if (= version :v3)
                       (testcat/munged-canonical->wire-format version original-catalog)
                       (testcat/munge-catalog-for-comparison version result))
            result (if (= version :v3)
                     (testcat/munged-canonical->wire-format version (update-in
                                                                     (cats/parse-catalog body 3)
                                                                     [:resources] vals))
                     (testcat/munge-catalog-for-comparison version result))]
        (is (= original result))))))

(deftestseq catalog-not-found
  [[version endpoint] endpoints
   :let [result (get-response endpoint "something-random.com")]]

  (is (= 404 (:status result)))
  (is (re-find #"Could not find catalog" (-> (:body result)
                                             (json/parse-string true)
                                             :error))))
