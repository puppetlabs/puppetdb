(ns com.puppetlabs.puppetdb.test.http.v3.resources
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.resources :only [store-example-resources]]))

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v3/resources" query))))

(deftest test-resource-queries
  (let [expected (store-example-resources)]
    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    "/v3/resources"
                         :limit   2
                         :total   (count expected)
                         :include-count-header  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set results))))))))


