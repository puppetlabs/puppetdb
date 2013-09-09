(ns com.puppetlabs.puppetdb.test.http.v3.facts
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  "Return a GET request against path, suitable as an argument to a ring
  app. Params supported are content-type and query-string."
  ([path] (get-request path {}))
  ([path params]
    (let [request (request :get path params)]
      (update-in request [:headers] assoc "Accept" c-t))))

(defn paged-results
  [query]
  (reduce
    (fn [coll n]
      (let [request (get-request "/v3/facts"
                      {:query (json/generate-string query) :limit 2 :offset (* 2 n)})
            {:keys [status body]} (*app* request)
            result  (json/parse-string body)]
        (is (>= 2 (count result)))
        (concat coll result)))
    []
    (range 3)))

(deftest fact-queries
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

    (testing "should support paging through facts"
      (let [results (paged-results ["=" "certname" "foo1"])]
        (is (= (count facts1) (count results)))
        (is (= (set (map (fn [[k v]] {"certname" "foo1"
                               "name" k
                               "value" v})
                        facts1))
              (set results)))))))
