(ns com.puppetlabs.puppetdb.test.http.v3.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [paged-results get-request]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def endpoint "/v3/fact-names")

(use-fixtures :each with-test-db with-http-app)

(deftest fact-names-queries
  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "uptime_seconds" "4000"}]

    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-facts! "foo1" facts1 (now)))

    (doseq [[label count?] [["without" false]
                             ["with" true]]]
      (testing (str "should support paging through facts " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    endpoint
                         :limit   2
                         :total   (count facts1)
                         :include-total  count?})]
          (is (= results (sort (keys facts1)))))))))
