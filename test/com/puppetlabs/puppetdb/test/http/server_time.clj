(ns com.puppetlabs.puppetdb.test.http.server-time
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [clj-time.core :refer [ago secs interval in-secs]]
            [clj-time.coerce :refer [from-string]]))

(def v3-endpoint "/v3/server-time")
(def v4-endpoint "/v4/server-time")
(def endpoints [v3-endpoint v4-endpoint])

(fixt/defixture super-fixture :each fixt/with-test-db fixt/with-http-app)

(deftest server-time-response
  (doseq [endpoint endpoints]
    (super-fixture
     (fn []
       (testing (str "endpoint " endpoint)
         (let [test-time (ago (secs 1))
               response  (fixt/*app* (tu/get-request endpoint))]
           (tu/assert-success! response)
           (let [server-time (-> response
                                 :body
                                 (json/parse-string true)
                                 :server-time
                                 from-string)]
             (is (> (in-secs (interval test-time server-time)) 0))
             (is (> 5 (in-secs (interval test-time server-time)))))))))))

