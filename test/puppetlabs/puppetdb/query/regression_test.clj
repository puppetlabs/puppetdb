(ns puppetlabs.puppetdb.query.regression-test
  (:require
   [clj-yaml.core :as yaml]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.testutils.db :refer [with-test-db]]
   [puppetlabs.puppetdb.testutils.http
    :refer [query-response with-http-app]]))

(deftest collected-queries-still-working
  (with-test-db
    (with-http-app
      (doseq [file (->> "locust/load-test"
                        io/as-file
                        file-seq
                        (filter #(str/ends-with? % ".yaml")))
              request-data (yaml/parse-string (slurp file))
              :let [{:keys [alias method path query]} request-data
                    keyword-method (case method
                                     "GET" :get
                                     "POST" :post)
                    relative-path (str/replace-first path #"^/pdb/query" "")]]
        (testing (str "testing " alias " in " file)
          (let [{:keys [status body]}
                (query-response keyword-method relative-path query)]
            ;; If body of response is not valid JSON, the test should fail
            (is (-> body slurp json/parse-string))
            ;; If status of response is not 200, the test should fail
            (is (= 200 status))))))))

