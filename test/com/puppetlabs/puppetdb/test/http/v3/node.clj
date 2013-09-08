(ns com.puppetlabs.puppetdb.test.http.v3.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.testutils :only [assert-success! get-request paged-results]]
        [com.puppetlabs.puppetdb.testutils.node :only [store-example-nodes]]))

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v3/nodes" query))))

(deftest test-node-queries
  (let [expected (store-example-nodes)]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through nodes " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    "/v3/nodes"
                         :limit   1
                         :total   (count expected)
                         :count?  count?})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                (set (map :name results)))))))))


