(ns com.puppetlabs.puppetdb.test.http.v3.fact-names
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))


(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn make-request
  "Return a GET request against path, suitable as an argument to a ring
  app. Params supported are content-type and query-string."
  ([path] (make-request path {}))
  ([path params]
    (let [request (request :get path params)]
      (update-in request [:headers] assoc "Accept" c-t))))

(defn paged-results
  []
  (reduce
    (fn [coll n]
      (let [request (make-request "/v3/fact-names"
                      {:limit 2 :offset (* 2 n)})
            {:keys [status body]} (*app* request)
            result (json/parse-string body)]
        (is (>= 2 (count result)))
        (concat coll result)))
    []
    (range 3)))

(deftest all-fact-names
  (let [facts {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "uptime_seconds" "4000"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-facts! "foo1" facts (now)))

    (testing "should support paging through facts"
      (let [results (paged-results)]
        (is (= results (sort (keys facts))))))))
