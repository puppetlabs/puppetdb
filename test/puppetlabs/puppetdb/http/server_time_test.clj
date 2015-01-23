(ns puppetlabs.puppetdb.http.server-time-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.testutils :refer [get-request deftestseq
                                                   assert-success!]]
            [clj-time.core :refer [ago secs interval in-secs]]
            [clj-time.coerce :refer [from-string]]))

(def endpoints [[:v4 "/v4/server-time"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(deftestseq server-time-response
  [[version endpoint] endpoints]

  (let [test-time (ago (secs 1))
        response  (fixt/*app* (get-request endpoint))]
    (assert-success! response)
    (let [server-time (-> response
                          :body
                          (json/parse-string true)
                          :server_time
                          from-string)]
      (is (> (in-secs (interval test-time server-time)) 0))
      (is (> 5 (in-secs (interval test-time server-time)))))))

