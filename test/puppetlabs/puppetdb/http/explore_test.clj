(ns puppetlabs.puppetdb.http.explore-test
  (:import [com.fasterxml.jackson.core JsonParseException])
  (:require [cheshire.core :as json]
            ring.middleware.params
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [flatland.ordered.map :as omap]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.testutils :refer [assert-success! get-request]]
            [puppetlabs.puppetdb.examples :refer :all]))

(use-fixtures :each with-test-db with-http-app)

(def c-t http/json-response-content-type)

(defn get-versioned-response
  [version route]
  (let [endpoint (str "/" (name version) "/" route)
        resp (*app*
              (get-request endpoint nil {} {"Accept" c-t}))]
    (if (string? (:body resp))
      resp
      (update-in resp [:body] slurp))))

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
       (assert-success! response#)
       (is (= c-t (get-in response# [:headers "Content-Type"])))
       (do ~@the-body))))

(deftest test-exploration
  (let [catalog (:basic catalogs)
        facts   {"kernel"          "Linux"
                 "operatingsystem" "Debian"}
        facts1  (assoc facts "fqdn" "host1")
        facts2  (assoc facts "fqdn" "host2")
        facts3  (assoc facts "fqdn" "host3")
        cat1    (assoc catalog :name "host1")
        cat2    (assoc catalog :name "host2")
        cat3    (assoc catalog :name "host3")]
    (scf-store/add-certname! "host1")
    (scf-store/add-certname! "host2")
    (scf-store/add-certname! "host3")
    (scf-store/replace-catalog! cat1 (now))
    (scf-store/replace-catalog! cat2 (now))
    (scf-store/replace-catalog! cat3 (now))
    (scf-store/add-facts! {:name "host1"
                           :values facts1
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "host2"
                           :values facts2
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-facts! {:name "host3"
                           :values facts3
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/deactivate-node! "host3")

    (let [version :v4
          get-response (partial get-versioned-response version)]
      (testing "/nodes should return all active nodes"
        (check-json-response
         nodes response (get-response "nodes")
         (is (= (set (mapv :certname nodes)) #{"host1" "host2"}))))

      (testing "/nodes/<node> should return status info"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           status response (get-response (str "nodes/" host))
           (is (= host (:certname status)))
           (is (nil? (:deactivated status)))))
        ;; host3 should be deactivated
        (check-json-response
         status response (get-response "nodes/host3")
         (is (= "host3" (:certname status)))
         (is (:deactivated status))))

      (testing "/nodes/<node> should return a 404 for unknown nodes"
        (let [response (get-response "nodes/host4")]
          (is (= 404 (:status response)))
          (is (= {:error "No information is known about host4"} (json/parse-string (:body response) true)))))

      (testing "/nodes/<node>/resources should return the resources just for that node"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           resources response (get-response (format "nodes/%s/resources" host))
           ;; The certname for each resource should be the current host
           (is (= (set (map :certname resources)) #{host})))))

      (testing "/nodes/<node>/resources/<type> should return the resources just for that node matching the supplied type"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           resources response (get-response (format "nodes/%s/resources/File" host))
           ;; The certname for each resource should be the current host
           (is (= (set (map :certname resources)) #{host}))
           (is (= (set (map :type resources)) #{"File"}))
           (is (= (count resources) 2)))))

      (testing "/nodes/<node>/resources/<type>/<title> should return the resources just for that node matching the supplied type/title"
        (doseq [host ["host1" "host2"]]
          (check-json-response
           resources response (get-response (format "nodes/%s/resources/File/%%2Fetc%%2Ffoobar" host))
           ;; The certname for each resource should be the current host
           (is (= (set (map :certname resources)) #{host}))
           (is (= (set (map :type resources)) #{"File"}))
           (is (= (set (map :title resources)) #{"/etc/foobar"}))
           (is (= (set (map :file resources)) #{"/tmp/foo"}))
           (is (= (set (map :line resources)) #{10}))
           (is (= (count resources) 1)))))

      (testing "/resources without a query should not fail"
        (let [response (get-response "resources")]
          (is (= 200 (:status response)))))

      (testing "/resources/<type> should return all resources matching the supplied type"
        (check-json-response
         resources response (get-response "resources/File")
         (is (= (set (map :certname resources)) #{"host1" "host2"}))
         (is (= (set (map :type resources)) #{"File"}))
         (is (= (count resources) 4))))

      (testing "/resources/<type> should return [] if the <type> doesn't match anything"
        (check-json-response
         resources response (get-response "resources/Foobar")
         (is (= resources []))))

      (testing "/resources/<type>/<title> should return all resources matching the supplied type/title"
        (check-json-response
         resources response (get-response "resources/File/%2Fetc%2Ffoobar")
         (is (= (set (map :certname resources)) #{"host1" "host2"}))
         (is (= (set (map :type resources)) #{"File"}))
         (is (= (set (map :title resources)) #{"/etc/foobar"}))
         (is (= (count resources) 2))))

      (testing "/resources/<type>/<title> should return [] if the <type> and <title> don't match anything"
        (check-json-response
         resources response (get-response "resources/File/mehmeh")
         (is (= resources []))))

      (let [treeify-facts (fn [facts]
                            ;; Create a tree-structure for facts: node -> name -> value
                            (reduce #(assoc-in %1 [(:certname %2) (:name %2)] (:value %2)) {} facts))]

        (testing "/facts without a query should not fail"
          (let [response (get-response "facts")]
            (is (= 200 (:status response)))))

        (testing "/facts/<fact> should return all instances of the given fact"
          (check-json-response
           facts response (get-response "facts/kernel")
           (is (= (set (map :name facts)) #{"kernel"}))
           (is (= (count facts) 2))))

        (testing "/facts/<fact>/<value> should return all instances of the given fact with the given value"
          (check-json-response
           facts response (get-response "facts/kernel/Linux")
           (is (= (set (map :name facts)) #{"kernel"}))
           (is (= (set (map :value facts)) #{"Linux"}))
           (is (= (count facts) 2))))

        (testing "/facts/<fact> should return [] if the fact doesn't match anything"
          (check-json-response
           facts response (get-response "facts/blahblahblah")
           (is (= facts []))))

        (testing "/nodes/<node>/facts should return the facts just for that node"
          (doseq [host ["host1" "host2"]]
            (check-json-response
             facts response (get-response (format "nodes/%s/facts" host))
             ;; The fqdn fact should be the name of the host
             (is (= (get-in (treeify-facts facts) [host "fqdn"]) host))
             (is (= (set (map :certname facts)) #{host}))
             (is (= (count facts) 3)))))

        (testing "/nodes/<node>/fact/<fact> should return the given fact for that node"
          (doseq [host ["host1" "host2"]]
            (check-json-response
             facts response (get-response (format "nodes/%s/facts/kernel" host))
             (is (= (get-in (treeify-facts facts) [host "kernel"]) "Linux"))
             (is (= (set (map :certname facts)) #{host}))
             (is (= (count facts) 1)))))

        (testing "/nodes/<node>/fact/<fact>/<value> should return the given fact with the matching value for that node"
          (doseq [host ["host1" "host2"]]
            (check-json-response
             facts response (get-response (format "nodes/%s/facts/kernel/Linux" host))
             (is (= (get-in (treeify-facts facts) [host "kernel"]) "Linux"))
             (is (= (set (map :certname facts)) #{host}))
             (is (= (set (map :name facts)) #{"kernel"}))
             (is (= (set (map :value facts)) #{"Linux"}))
             (is (= (count facts) 1)))))))))
