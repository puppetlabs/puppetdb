(ns com.puppetlabs.puppetdb.test.http.experimental.explore
  (:require [cheshire.core :as json]
            ring.middleware.params
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.http :as pl-http])
  (:import [com.fasterxml.jackson.core JsonParseException])
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
  ([route] (*app* (get-request (str "/experimental/" route)))))

(defmacro check-json-response
  "Test if the HTTP request is a success, and if the result is equal
  to the result of the form supplied to this method."
  [body-var response-var compute-response & the-body]
  `(let [response# ~compute-response]
     (let [~response-var response#
           ~body-var (if-let [body# (:body response#)]
                       (try
                         (json/parse-string body# true)
                         (catch JsonParseException e#
                           body#)))]
       (is (= pl-http/status-ok (:status response#)))
       (is (= c-t (get-in response# [:headers "Content-Type"])))
       (do ~@the-body))))

(deftest test-exploration
  (let [catalog (:basic catalogs)
        facts   {"kernel"          "Linux"
                 "operatingsystem" "Debian"}
        facts1  (assoc facts "fqdn" "host1")
        facts2  (assoc facts "fqdn" "host2")
        cat1    (assoc catalog :certname "host1")
        cat2    (assoc catalog :certname "host2")]
    (scf-store/add-certname! "host1")
    (scf-store/add-certname! "host2")
    (scf-store/replace-catalog! cat1 (now))
    (scf-store/replace-catalog! cat2 (now))
    (scf-store/add-facts! "host1" facts1 (now))
    (scf-store/add-facts! "host2" facts2 (now))

    (testing "/<node> should return status info"
      (doseq [host ["host1" "host2"]]
        (check-json-response
         status response (get-response (str "nodes/" host))
         (is (= host (:name status)))
         (is (nil? (:deactivated status))))))

    (testing "/<node>/resources should return the resources just for that node"
      (doseq [host ["host1" "host2"]]
        (check-json-response
         resources response (get-response (format "nodes/%s/resources" host))
         ;; The certname for each resource should be the current host
         (is (= (set (map :certname resources)) #{host})))))

    (testing "/<node>/resources/<type> should return the resources just for that node matching the supplied type"
      (doseq [host ["host1" "host2"]]
        (check-json-response
         resources response (get-response (format "nodes/%s/resources/File" host))
         ;; The certname for each resource should be the current host
         (is (= (set (map :certname resources)) #{host}))
         (is (= (set (map :type resources)) #{"File"}))
         (is (= (count resources) 2)))))

    (testing "/<node>/resources/<type>/<title> should return the resources just for that node matching the supplied type/title"
      (doseq [host ["host1" "host2"]]
        (check-json-response
         resources response (get-response (format "nodes/%s/resources/File/%%2Fetc%%2Ffoobar" host))
         ;; The certname for each resource should be the current host
         (is (= (set (map :certname resources)) #{host}))
         (is (= (set (map :type resources)) #{"File"}))
         (is (= (set (map :title resources)) #{"/etc/foobar"}))
         (is (= (count resources) 1)))))

    (testing "/resources without a query should fail"
       (let [response (get-response "resources")]
         (is (= 400 (:status response)))
         (is (= "missing query" (:body response)))))

    (testing "/resources/<type> should return all resources matching the supplied type"
      (check-json-response
       resources response (get-response "resources/File")
       (is (= (set (map :certname resources)) #{"host1" "host2"}))
       (is (= (set (map :type resources)) #{"File"}))
       (is (= (count resources) 4))))

    (testing "/resources/<type>/<title> should return all resources matching the supplied type/title"
      (check-json-response
       resources response (get-response "resources/File/%2Fetc%2Ffoobar")
       (is (= (set (map :certname resources)) #{"host1" "host2"}))
       (is (= (set (map :type resources)) #{"File"}))
       (is (= (set (map :title resources)) #{"/etc/foobar"}))
       (is (= (count resources) 2))))

    (let [treeify-facts (fn [facts]
                          ;; Create a tree-structure for facts: node -> name -> value
                          (reduce #(assoc-in %1 [(:node %2) (:name %2)] (:value %2)) {} facts))]

      (testing "/facts without a query should fail"
        (let [response (get-response "facts")]
          (is (= 400 (:status response)))
          (is (= "missing query" (:body response)))))

      (testing "/facts/<fact> should return all instances of the given fact"
        (check-json-response
         facts response (get-response "facts/kernel")
         (is (= (set (map :name facts)) #{"kernel"}))
         (is (= (count facts) 2))))

      (testing "/<node>/facts should return the facts just for that node"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           facts response (get-response (format "nodes/%s/facts" host))
           ;; The fqdn fact should be the name of the host
           (is (= (get-in (treeify-facts facts) [host "fqdn"]) host))
           (is (= (set (map :node facts)) #{host}))
           (is (= (count facts) 3)))))

      (testing "/<node>/fact/<fact> should return the given fact for that node"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           facts response (get-response (format "nodes/%s/facts/kernel" host))
           (is (= (get-in (treeify-facts facts) [host "kernel"]) "Linux"))
           (is (= (set (map :node facts)) #{host}))
           (is (= (count facts) 1))))))))
