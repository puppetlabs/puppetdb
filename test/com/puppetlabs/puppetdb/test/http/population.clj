(ns com.puppetlabs.puppetdb.test.http.population
  (:require [cheshire.core :as json]
            ring.middleware.params
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  [path]
  (let [request (request :get path)]
    (update-in request [:headers] assoc "Accept" c-t)))

(defn get-response
  ([]      (get-response nil))
  ([route] (*app* (get-request (str "/experimental/population/" route)))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= pl-http/status-ok   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= (when-let [body (:body response)]
           (json/parse-string body true))
         body)))

(deftest collected-exported-resources
  (let [catalog (:basic catalogs)
        resources (:resources catalog)
        collector-catalog (assoc catalog :certname "collector")
        exporter-catalog (-> catalog
                           (assoc :certname "exporter")
                           (assoc :resources (into {} (for [[spec resource] resources]
                                                        [spec (assoc resource :exported true)]))))]
    (scf-store/add-certname! "collector")
    (scf-store/add-certname! "exporter")
    (scf-store/store-catalog-for-certname! collector-catalog (now))
    (scf-store/store-catalog-for-certname! exporter-catalog (now))

    (testing "should return a list of resources and who exports/collects them"
      (is-response-equal (get-response "exported-resources")
                         [{:type "Class" :title "foobar" :exporter "exporter" :collector "collector"}
                          {:type "File" :title "/etc/foobar" :exporter "exporter" :collector "collector"}
                          {:type "File" :title "/etc/foobar/baz" :exporter "exporter" :collector "collector"}]))))
