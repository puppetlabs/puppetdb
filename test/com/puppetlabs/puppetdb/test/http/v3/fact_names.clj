(ns com.puppetlabs.puppetdb.test.http.v3.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [paged-results]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(use-fixtures :each with-test-db with-http-app)


(deftest all-fact-names
  (let [facts {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "uptime_seconds" "4000"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-facts! "foo1" facts (now)))

    (doseq [[label count?] [["without" false]
                             ["with" true]]]
      (testing (str "should support paging through facts " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    "/v3/fact-names"
                         :limit   2
                         :total   (count facts)
                         :include-count-header  count?})]
          (is (= results (sort (keys facts)))))))))
