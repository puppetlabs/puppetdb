(ns com.puppetlabs.puppetdb.test.http.v3.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [get-request paged-results]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def endpoint "/v3/facts")

(use-fixtures :each with-test-db with-http-app)

(defn test-paged-results
  [query limit total count?]
  (paged-results
    {:app-fn  *app*
     :path    endpoint
     :query   query
     :limit   limit
     :total   total
     :include-total  count?}))

(deftest fact-query-paging
  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "some_version" "1.3.7+build.11.e0f985a"
                "uptime_seconds" "4000"}
        facts2 {"domain" "testing.com"
                "hostname" "foo2"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" "6000"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now)))

    (doseq [[label counts?] [["without" false]
                             ["with" true]]]
      (testing (str "should support paging through facts " label " counts")
        (let [results (test-paged-results
                        ["=" "certname" "foo1"]
                        2 (count facts1) counts?)]
          (is (= (count facts1) (count results)))
          (is (= (set (map (fn [[k v]]
                             {:certname "foo1"
                              :name     k
                              :value    v})
                          facts1))
                (set results))))))))
