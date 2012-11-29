(ns com.puppetlabs.puppetdb.test.http.v2.node
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http])
  (:use clojure.test
        ring.mock.request
        [clj-time.core :only [now]]
        com.puppetlabs.puppetdb.examples
        com.puppetlabs.puppetdb.fixtures))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (json/generate-string query)})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v2/nodes" query))))

(deftest node-subqueries
  (let [node1 "foo"
        node2 "bar"
        node3 "baz"
        catalog (:empty catalogs)
        catalog1 (update-in catalog [:resources] conj {{:type "Class" :title "web"} {:type "Class" :title "web" :exported false}})
        catalog2 (update-in catalog [:resources] conj {{:type "Class" :title "puppet"} {:type "Class" :title "puppetmaster" :exported false}})
        catalog3 (update-in catalog [:resources] conj {{:type "Class" :title "db"} {:type "Class" :title "mysql" :exported false}})]
    (scf-store/add-certname! node1)
    (scf-store/add-certname! node2)
    (scf-store/add-certname! node3)
    (scf-store/add-facts! node1 {"ipaddress" "192.168.1.100" "hostname" "web" "operatingsystem" "Debian"} (now))
    (scf-store/add-facts! node2 {"ipaddress" "192.168.1.100" "hostname" "puppet" "operatingsystem" "RedHat"} (now))
    (scf-store/add-facts! node3 {"ipaddress" "192.168.1.100" "hostname" "db" "operatingsystem" "Debian"} (now))
    (scf-store/replace-catalog! (assoc catalog1 :certname node1) (now))
    (scf-store/replace-catalog! (assoc catalog2 :certname node2) (now))
    (scf-store/replace-catalog! (assoc catalog3 :certname node3) (now))

    ;; Nodes with the operatingsystem Debian
    (doseq [[query expected] {["in" "name"
                               ["extract" "certname"
                                ["select-facts"
                                 ["and"
                                  ["=" "name" "operatingsystem"]
                                  ["=" "value" "Debian"]]]]]

                              [node3 node1]

                              ;; Nodes with a class matching their hostname
                              ["in" "name"
                               ["extract" "certname"
                                ["select-facts"
                                 ["and"
                                  ["=" "name" "hostname"]
                                  ["in" "value"
                                   ["extract" "title"
                                    ["select-resources"
                                     ["and"
                                      ["=" "type" "Class"]]]]]]]]]

                              [node1]}]
      (let [{:keys [body status]} (get-response query)]
        (is (= status pl-http/status-ok))
        (is (= (json/parse-string body true) expected)
            body)))))
