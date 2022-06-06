(ns puppetlabs.puppetdb.http.sync-version-checking
  (:require  [clojure.test :refer :all]
             [puppetlabs.puppetdb.constants :as constants]
             [puppetlabs.puppetdb.http :as http]
             [puppetlabs.puppetdb.testutils.http
              :refer [deftest-http-app query-response *app*]]
             [puppetlabs.puppetdb.testutils :refer [query-request]]))

(def endpoints [[:v4 "/v4"]])

(deftest-http-app sync-version-checking-headers
  [[_version endpoint] endpoints
   method [:get :post]]
  (let [req-with-sync-ver (fn [ver]
                            (*app*
                             (query-request
                              method endpoint
                              ["from" "nodes"]
                              {:headers {"x-pdb-sync-ver" ver}})))
        sync-ver constants/pdb-sync-ver]

    (testing "should succeed when sync versions are equal"
      (is (= 200 (:status (req-with-sync-ver (str sync-ver))))))

    (testing "should succed when sync version missing"
      (is (= 200 (:status (query-response method endpoint ["from" "nodes"])))))

    (testing "should fail when sync versions mismatched"
      (is (= (req-with-sync-ver (str (inc sync-ver)))
             {:status 409
              :headers {"Content-Type" http/error-response-content-type}
              :body
              "Conflicting PDB sync versions, each PDB syncing must be on the same version"})))

    (testing "should fail when x-pdb-sync-ver input is invalid"
      (is (= (req-with-sync-ver "abc")
             {:status 400
              :headers {"Content-Type" http/error-response-content-type}
              :body
              "The x-pdb-sync-ver header: abc cannot be converted to an int."})))))
