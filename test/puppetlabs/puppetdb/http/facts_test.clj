(ns puppetlabs.puppetdb.http.facts-test
  (:require [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.http :as http]
            [cheshire.core :as json]
            [clojure.core.match :as cm]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.http.server :as server]
            [clojure.java.io :as io]
            [flatland.ordered.map :as omap]
            [clj-time.coerce :refer [to-timestamp to-string]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.query :refer [remove-all-environments]]
            [clojure.string :as string]
            [puppetlabs.puppetdb.utils :as utils]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.examples :refer :all]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils :refer [get-request assert-success!
                                                   paged-results paged-results*
                                                   deftestseq parse-result]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]))

(def v2-facts-endpoint "/v2/facts")
(def v3-facts-endpoint "/v3/facts")
(def v4-facts-endpoint "/v4/facts")
(def v4-facts-environment "/v4/environments/DEV/facts")
(def facts-endpoints [[:v2 v2-facts-endpoint]
                      [:v3 v3-facts-endpoint]
                      [:v4 v4-facts-endpoint]
                      [:v4 v4-facts-environment]])

(def factsets-endpoints [[:v4 "/v4/factsets"]])

(def fact-contents-endpoints [[:v4 "/v4/fact-contents"]])

(use-fixtures :each with-test-db with-http-app)

(def c-t http/json-response-content-type)

(defn get-response
  ([endpoint]      (get-response endpoint nil))
  ([endpoint query] (*app* (get-request endpoint query)))
  ([endpoint query params] (*app* (get-request endpoint query params))))

(defn is-query-result
  [endpoint query expected-results]
  (let [request (get-request endpoint (json/generate-string query))
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count actual-result) (count expected-results)))
    (is (= (set actual-result) expected-results))
    (is (= status http/status-ok))))

(defn munge-structured-response
  [row]
  (let [value (get row :value)]
    (if (= (first value) \{)
      (update-in row [:value] json/parse-string)
      row)))

(defn compare-structured-response
  "compare maps that may have been stringified differently."
  [response expected version]
  (case version
    (:v2 :v3)
    (is (= (map munge-structured-response response)
           (map munge-structured-response expected)))

    (is (= response expected))))

(def common-subquery-tests
  (omap/ordered-map
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["and"
                                             ["=" "type" "Class"]
                                             ["=" "title" "Apache"]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}}

   ;; "not" matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["not"
     ["in" "certname" ["extract" "certname" ["select-resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Apache"]]]]]]]

   #{{:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; Multiple matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["=" "type" "Class"]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; Multiple facts
   ["and"
    ["or"
     ["=" "name" "ipaddress"]
     ["=" "name" "operatingsystem"]]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["and"
                                             ["=" "type" "Class"]
                                             ["=" "title" "Apache"]]]]]]

   #{{:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
     {:certname "bar" :name "operatingsystem" :value "Ubuntu" :environment "DEV"}
     {:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "foo" :name "operatingsystem" :value "Debian" :environment "DEV"}}

   ;; Multiple subqueries
   ["and"
    ["=" "name" "ipaddress"]
    ["or"
     ["in" "certname" ["extract" "certname" ["select-resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Apache"]]]]]
     ["in" "certname" ["extract" "certname" ["select-resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Main"]]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; No matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["=" "type" "NotRealAtAll"]]]]]
   #{}

   ;; No matching facts
   ["and"
    ["=" "name" "nosuchfact"]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["=" "type" "Class"]]]]]
   #{}

   ;; Fact subquery
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}}

   ;; Using a different column
   ["in" "name" ["extract" "name" ["select-facts"
                                   ["=" "name" "osfamily"]]]]

   #{{:certname "bar" :name "osfamily" :value "Debian" :environment "DEV"}
     {:certname "baz" :name "osfamily" :value "RedHat" :environment "DEV"}
     {:certname "foo" :name "osfamily" :value "Debian" :environment "DEV"}}

   ;; Nested fact subqueries
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]
                                             ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                     ["and"
                                                                                      ["=" "name" "uptime_seconds"]
                                                                                      [">" "value" 10000]]]]]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}}
   ;; Multiple fact subqueries
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]]]]]
    ["in" "certname" ["extract" "certname" ["select-facts"
                                            ["and"
                                             ["=" "name" "uptime_seconds"]
                                             [">" "value" 10000]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}}))

(def versioned-subqueries
  (omap/ordered-map
   "/v2/facts"
   (merge common-subquery-tests
          (omap/ordered-map
           ;; Subqueries using file/line
           ["and"
            ["=" "name" "ipaddress"]
            ["in" "certname" ["extract" "certname" ["select-resources"
                                                    ["and"
                                                     ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                                                     ["=" "sourceline" 1]]]]]]

           #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
             {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
             {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}))

   "/v3/facts"
   (merge common-subquery-tests
          (omap/ordered-map
           ;; Subqueries using file/line
           ["and"
            ["=" "name" "ipaddress"]
            ["in" "certname" ["extract" "certname" ["select-resources"
                                                    ["and"
                                                     ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
                                                     ["=" "line" 1]]]]]]

           #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
             {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
             {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}))

   "/v4/facts"
   (merge common-subquery-tests
          (omap/ordered-map
           ;; vectored fact-contents subquery
           ["in" ["name" "certname"]
            ["extract" ["name" "certname"]
             ["select-fact-contents"
              ["and" ["<" "value" 10000] ["~>" "path" ["up.*"]]]]]]
           #{{:value 12, :name "uptime_seconds", :environment "DEV", :certname "bar"}}))))

(def versioned-invalid-subqueries
  (omap/ordered-map
   "/v2/facts" (omap/ordered-map
                ;; Extract using an invalid field should throw an error
                ["in" "certname" ["extract" "nothing" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't extract unknown resource field 'nothing'. Acceptable fields are: catalog, certname, environment, exported, resource, sourcefile, sourceline, tags, title, type"

                ;; In-query for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, depth, environment, name, path, type, value, value_float, value_integer")

   "/v3/facts" (omap/ordered-map
                ;; Extract using an invalid fields should throw an error
                ["in" "certname" ["extract" "nothing" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't extract unknown resource field 'nothing'. Acceptable fields are: catalog, certname, environment, exported, file, line, resource, tags, title, type"

                ;; Subqueries using old sourcefile/sourceline should throw error
                ["and"
                 ["=" "name" "ipaddress"]
                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                         ["and"
                                                          ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                                                          ["=" "sourceline" 1]]]]]]

                "'sourcefile' is not a queryable object for resources in the version 3 API"

                ;; vectored fact-contents subquery
                ["in" ["name" "certname"]
                 ["extract" ["name" "certname"]
                  ["select-fact-contents"
                   ["and" ["<" "value" 10000] ["~>" "path" ["up.*"]]]]]]
                "Can't match on fields '[\"name\" \"certname\"]'. The v2-v3 query API does not permit vector-valued fields."

                ;; In-queries for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, depth, environment, name, path, type, value, value_float, value_integer")))

(def common-well-formed-tests
  (omap/ordered-map
   ["=" "name" "domain"]
   [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
    {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
    {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}]

   ["=" "value" "Darwin"]
   [{:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ["and" ["=" "name" "kernel"]
    ["~" "value" "i.u[xX]"]]
   [{:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}]

   ["~" "name" "^ho\\wt.*e$"]
   [{:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
    {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
    {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}]

   ;; heinous regular expression to detect semvers
   ["~" "value" "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"]
   [{:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}]

   ["and" ["=" "name" "hostname"]
    ["~" "certname" "^foo[12]$"]]
   [{:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
    {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}]

   ["and" ["=" "name" "hostname"]
    ["not" ["~" "certname" "^foo[12]$"]]]
   [{:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}]

   ["or" ["=" "name" "kernel"]
    ["=" "name" "operatingsystem"]]
   [{:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
    {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
    {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ;; Verify that we're enforcing that
   ;; facts from inactive nodes are never
   ;; returned, even if you ask for them
   ;; specifically.
   ["=" ["node" "active"] false]
   []))


(defn versioned-well-formed-tests
  [version]
  (case version
    (:v2 :v3)
    (merge
     common-well-formed-tests
     (omap/ordered-map
      nil
      [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
       {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
       {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
       {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}
       {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
       {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo1":name "bigstr" :value "1000000" :environment "DEV"}
       {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
       {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}
       {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
       {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
       {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

      ["not" ["=" "name" "domain"]]
      [{:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
       {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
       {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
       {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}
       {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
       {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo1" :name "bigstr" :value "1000000" :environment "DEV"}
       {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
       {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}
       {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
       {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
       {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

      ;; test that stringified numerical comparisons work for lower endpoints
      ["and" ["=" "name" "bigstr"]
       [">=" "value" "100000"]]
      [{:value "1000000", :name "bigstr", :certname "foo1"}]

      ["and" ["=" "name" "bigstr"]
       [">=" "value" 10000]]
      [{:value "1000000", :name "bigstr", :certname "foo1"}]

      ["and" ["=" "name" "uptime_seconds"]
       [">=" "value" "4000"]
       ["<" "value" 6000.0]]
      [{:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}]

      ["and" ["=" "name" "domain"]
       [">" "value" 5000]]
      []

      ["=" "certname" "foo2"]
      [{:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
       {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
       {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}]

      ["=" ["node" "active"] true]
      [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
       {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
       {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
       {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}
       {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
       {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
       {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
       {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}
       {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}
       {:certname "foo1" :name "bigstr" :value "1000000" :environment "DEV"}
       {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
       {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
       {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]))

    (merge common-well-formed-tests
           (omap/ordered-map
            nil
            [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
             {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
             {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
             {:certname "foo1" :name "uptime_seconds" :value 4000 :environment "DEV"}
             {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
             {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
             {:certname "foo2" :name "uptime_seconds" :value 6000 :environment "DEV"}
             {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo1" :name "bigstr" :value "1000000" :environment "DEV"}
             {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
             {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
             {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

            ["not" ["=" "name" "domain"]]
            [{:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
             {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
             {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
             {:certname "foo1" :name "uptime_seconds" :value 4000 :environment "DEV"}
             {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
             {:certname "foo1" :name "bigstr" :value "1000000" :environment "DEV"}
             {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
             {:certname "foo2" :name "uptime_seconds" :value 6000 :environment "DEV"}
             {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
             {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
             {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

            ["and" ["=" "name" "uptime_seconds"]
             [">" "value" 5000]]
            [{:certname "foo2" :name "uptime_seconds" :value 6000 :environment "DEV"}]

            ["and" ["=" "name" "uptime_seconds"]
             [">=" "value" 4000]
             ["<" "value" 6000.0]]
            [{:certname "foo1" :name "uptime_seconds" :value 4000 :environment "DEV"}]

            ["and" ["=" "name" "domain"]
             [">" "value" 5000]]
            []

            ["=" "certname" "foo2"]
            [{:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
             {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
             {:certname "foo2" :name "uptime_seconds" :value 6000 :environment "DEV"}]

            ["=" ["node" "active"] true]
            [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
             {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
             {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
             {:certname "foo1" :name "uptime_seconds" :value 4000 :environment "DEV"}
             {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo1" :name "bigstr" :value "1000000" :environment "DEV"}
             {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
             {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
             {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
             {:certname "foo2" :name "uptime_seconds" :value 6000 :environment "DEV"}
             {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}
             {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
             {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
             {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]))))

(defn test-app
  ([read-write-db]
     (test-app read-write-db read-write-db))
  ([read-db write-db]
     (server/build-app
      :globals {:scf-read-db          read-db
                :scf-write-db         write-db
                :command-mq           *mq*
                :product-name         "puppetdb"})))

(defn with-shutdown-after [dbs f]
  (f)
  (doseq [db dbs]
    (sql/with-connection db
      (sql/do-commands "SHUTDOWN"))))

(deftestseq fact-queries
  [[version endpoint] facts-endpoints]

  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "bigstr" "1000000"
                "operatingsystem" "Debian"
                "some_version" "1.3.7+build.11.e0f985a"
                "uptime_seconds" 4000}
        facts2 {"domain" "testing.com"
                "hostname" "foo2"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" 6000}
        facts3 {"domain" "testing.com"
                "hostname" "foo3"
                "kernel" "Darwin"
                "operatingsystem" "Darwin"}
        facts4 {"domain" "testing.com"
                "hostname" "foo4"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" 6000}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:name "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name  "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo4"
                             :values facts4
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (get-response endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact queries"
      (testing "well-formed queries"
        (doseq [[query result] (versioned-well-formed-tests version)]
          (testing (format "Query %s" query)
            (let [request (get-request endpoint (json/generate-string query))
                  {:keys [status body headers]} (*app* request)]
              (is (= status http/status-ok))
              (is (= (headers "Content-Type") c-t))
              (is (= (set (remove-all-environments version result))
                     (set (json/parse-string (slurp body) true))))))))

      (testing "malformed, yo"
        (let [request (get-request endpoint (json/generate-string []))
              {:keys [status body]} (*app* request)]
          (is (= status http/status-bad-request))
          (is (= body "[] is not well-formed: queries must contain at least one operator"))))

      (testing "'not' with too many arguments"
        (let [request (get-request endpoint (json/generate-string ["not" ["=" "name" "ipaddress"] ["=" "name" "operatingsystem"]]))
              {:keys [status body]} (*app* request)]
          (is (= status http/status-bad-request))
          (is (= body "'not' takes exactly one argument, but 2 were supplied")))))))

(deftestseq fact-subqueries
  [[version endpoint] facts-endpoints]

  (scf-store/add-certname! "foo")
  (scf-store/add-certname! "bar")
  (scf-store/add-certname! "baz")
  (scf-store/add-facts! {:name "foo"
                         :values {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer-timestamp nil})
  (scf-store/add-facts! {:name "bar"
                         :values {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12}
                         :timestamp (now)
                         :environment "DEV"
                         :producer-timestamp nil})
  (scf-store/add-facts! {:name "baz"
                         :values {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer-timestamp nil})

  (let [catalog (:empty catalogs)
        apache-resource {:type "Class" :title "Apache"}
        apache-catalog (update-in catalog [:resources] conj {apache-resource (assoc apache-resource :exported false)})]
    (scf-store/replace-catalog! (assoc apache-catalog :name "foo") (now))
    (scf-store/replace-catalog! (assoc apache-catalog :name "bar") (now))
    (scf-store/replace-catalog! (assoc catalog :name "baz") (now)))

  (doseq [[query results] (get versioned-subqueries endpoint)]
    (testing (str "query: " query " should match expected output")
      (is-query-result endpoint query (set (remove-all-environments version results)))))

  (testing "subqueries: invalid"
    (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
      (testing (str "query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body] :as result} (*app* request)]
          (is (= body msg))
          (is (= status http/status-bad-request)))))))

(deftestseq ^{:postgres false} two-database-fact-query-config
  [[version endpoint] facts-endpoints]

  (let [read-db (-> (create-db-map)
                    defaulted-read-db-config
                    (init-db true))
        write-db (-> (create-db-map)
                     defaulted-write-db-config
                     (init-db false))]
    (with-shutdown-after [read-db write-db]
      (fn []
        (let [one-db-app (test-app write-db)
              two-db-app (test-app read-db write-db)
              facts1 {"domain" "testing.com"
                      "hostname" "foo1"
                      "kernel" "Linux"
                      "operatingsystem" "Debian"
                      "some_version" "1.3.7+build.11.e0f985a"
                      "uptime_seconds" "4000"}]

          (with-transacted-connection write-db
            (scf-store/add-certname! "foo1")
            (scf-store/add-facts! {:name "foo1"
                                   :values facts1
                                   :timestamp (now)
                                   :environment "DEV"
                                   :producer-timestamp nil}))

          (testing "queries only use the read database"
            (let [request (get-request endpoint (json/parse-string nil))
                  {:keys [status body headers]} (two-db-app request)]
              (is (= status http/status-ok))
              (is (= (headers "Content-Type") c-t))
              (is (empty? (json/parse-stream (io/reader body) true)))))

          (testing "config with only a single database returns results"
            (let [request (get-request endpoint (json/parse-string nil))
                  {:keys [status body headers]} (one-db-app request)]
              (is (= status http/status-ok))
              (is (= (headers "Content-Type") c-t))
              (is (= (remove-all-environments
                      version
                      [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
                       {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
                       {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
                       {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
                       {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
                       {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}])
                     (sort-by :name (json/parse-stream (io/reader body) true)))))))))))

(defn test-paged-results
  [endpoint query limit total count?]
  (paged-results
   {:app-fn  *app*
    :path    endpoint
    :query   query
    :limit   limit
    :total   total
    :include-total  count?}))

(deftestseq fact-query-paging
  [[version endpoint] facts-endpoints]

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
      (scf-store/add-facts! {:name "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer-timestamp nil}))

    (when (not= version :v2)
      (testing "should support fact paging"
        (doseq [[label counts?] [["without" false]
                                 ["with" true]]]
          (testing (str "should support paging through facts " label " counts")
            (let [results (test-paged-results endpoint
                                              ["=" "certname" "foo1"]
                                              2 (count facts1) counts?)]
              (is (= (count facts1) (count results)))
              (is (= (set (remove-all-environments version (map (fn [[k v]]
                                                                  {:certname "foo1"
                                                                   :environment "DEV"
                                                                   :name     k
                                                                   :value    v})
                                                                facts1)))
                     (set results))))))))

    (when (= version :v2)
      (testing "should not support paging-related query parameters"
        (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
          (let [request (get-request endpoint nil {k v})
                {:keys [status body]} (*app* request)]
            (is (= status http/status-bad-request))
            (is (= body (format "Unsupported query parameter '%s'" (name k))))))))))

(defn- raw-query-endpoint
  [endpoint query paging-options]
  (let [{:keys [limit offset include-total]
         :or {limit Integer/MAX_VALUE
              include-total true
              offset 0}}  paging-options
              {:keys [headers body]} (paged-results* (assoc paging-options
                                                       :app-fn  *app*
                                                       :path    endpoint
                                                       :offset  offset
                                                       :limit   limit
                                                       :include-total include-total))]
    {:results body
     :count (when-let [rec-count (get headers "X-Records")]
              (ks/parse-int rec-count))}))

(defn unkeywordize-keys
  [m]
  (if-not (map? m) m
          (zipmap (map name (keys m))
                  (map unkeywordize-keys (vals m)))))

(defn unkeywordize-values
  [m]
  (if-not (map? m) m
          (zipmap (keys m)
                  (map (fn [x] (if (= x :value)
                                 (unkeywordize-keys (get m x))
                                 (unkeywordize-values (get m x)))) (keys m)))))

(defn- query-endpoint
  [endpoint paging-options]
  (:results (raw-query-endpoint endpoint nil paging-options)))

(deftestseq paging-results
  [[version endpoint] facts-endpoints
   :when (not= version :v2)]

  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host" :environment "DEV"}
        f2         {:certname "b.local" :name "uptime_days" :value "4" :environment "DEV"}
        f3         {:certname "c.local" :name "hostname"    :value "c-host" :environment "DEV"}
        f4         {:certname "d.local" :name "uptime_days" :value "2" :environment "DEV"}
        f5         {:certname "e.local" :name "my_structured_fact"
                    :value {"a" [1 2 3 4 5 6 7 8 9 10]} :environment "DEV"}
        fact-count 5]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! {:name "c.local"
                           :values {"hostname" "c-host"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:name "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:name "d.local"
                           :values {"uptime_days" "2"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:name "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})
    (scf-store/add-certname! "e.local")
    (scf-store/add-facts! {:name "e.local"
                           :values {"my_structured_fact" (:value f5)}
                           :timestamp (now)
                           :environment "DEV"
                           :producer-timestamp nil})

    (testing "include total results count"
      (let [actual (:count (raw-query-endpoint endpoint nil {:include-total true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-endpoint endpoint {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order-by.*"
                        (:body (*app* (get-request endpoint nil {:order-by (json/generate-string [{"field" "invalid-field" "order" "ASC"}])}))))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4 f5]]
                                  ["DESC" [f5 f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-endpoint endpoint
                                         {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}})]
              (compare-structured-response (map unkeywordize-values actual) (remove-all-environments version expected) version)))))

      (testing "multiple fields"
        (doseq [[[name-order certname-order] expected] [[["DESC" "ASC"]  [f2 f4 f5 f1 f3]]
                                                        [["DESC" "DESC"] [f4 f2 f5 f3 f1]]
                                                        [["ASC" "DESC"]  [f3 f1 f5 f4 f2]]
                                                        [["ASC" "ASC"]   [f1 f3 f5 f2 f4]]]]
          (testing (format "name %s certname %s" name-order certname-order)
            (let [actual (query-endpoint endpoint
                                         {:params {:order-by (json/generate-string [{"field" "name" "order" name-order}
                                                                                    {"field" "certname" "order" certname-order}])}})]
              (compare-structured-response (map unkeywordize-values actual) (remove-all-environments version expected) version))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [f1 f2 f3 f4 f5]]
                                                   [1 [f2 f3 f4 f5]]
                                                   [2 [f3 f4 f5]]
                                                   [3 [f4 f5]]
                                                   [4 [f5]]
                                                   [5 []]]]
                                          ["DESC" [[0 [f5 f4 f3 f2 f1]]
                                                   [1 [f4 f3 f2 f1]]
                                                   [2 [f3 f2 f1]]
                                                   [3 [f2 f1]]
                                                   [4 [f1]]
                                                   [5 []]]]]]

        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-endpoint endpoint
                                         {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}
                                          :offset offset})]
              (compare-structured-response (map unkeywordize-values actual) (remove-all-environments version expected) version))))
        (when-not (contains? #{:v2 :v3} version)
          (testing "rejects order by value on v4+"
            (is (re-matches #"Unrecognized column 'value' specified in :order-by.*"
                            (:body (*app*(get-request endpoint nil
                                                      {:order-by
                                                       (json/generate-string
                                                        [{"field" "value" "order" "ASC"}])})))))))))))

(deftestseq facts-environment-paging
  [[version endpoint] facts-endpoints
   :when (and (not (contains? #{:v2 :v3} version)) (not= endpoint v4-facts-environment))]

  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host" :environment "A"}
        f2         {:certname "b.local" :name "uptime_days" :value "4" :environment "B"}
        f3         {:certname "c.local" :name "my_structured_fact"
                    :value {"a" [1 2 3 4 5 6 7 8 9 10]} :environment "C"}
        f4         {:certname "b2.local" :name "max" :value "4" :environment "B"}
        f5         {:certname "d.local" :name "min" :value "-4" :environment "D"}]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! {:name "c.local"
                           :values {"my_structured_fact" (:value f3)}
                           :timestamp (now)
                           :environment "C"
                           :producer-timestamp nil})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:name "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "A"
                           :producer-timestamp nil})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:name "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer-timestamp nil})
    (scf-store/add-certname! "b2.local")
    (scf-store/add-facts! {:name "b2.local"
                           :values {"max" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer-timestamp nil})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:name "d.local"
                           :values {"min" "-4"}
                           :timestamp (now)
                           :environment "D"
                           :producer-timestamp nil})

    (testing "ordering by environment should work"
      (doseq [[[env-order name-order] expected] [[["DESC" "ASC"]  [f5 f3 f4 f2 f1]]
                                                 [["DESC" "DESC"]   [f5 f3 f2 f4 f1]]
                                                 [["ASC" "DESC"]  [f1 f2 f4 f3 f5]]
                                                 [["ASC" "ASC"]  [f1 f4 f2 f3 f5]]]]

        (testing (format "environment %s name %s" env-order name-order)
          (let [actual (query-endpoint
                        endpoint
                        {:params {:order-by
                                  (json/generate-string [{"field" "environment" "order" env-order}
                                                         {"field" "name" "order" name-order}])}})]
            (compare-structured-response (map unkeywordize-values actual)
                                         (remove-all-environments version expected) version)))))))

(deftestseq fact-environment-queries
  [[version endpoint] facts-endpoints
   :when (and (not-any? #(= version %) [:v2 :v3])
              (not #(re-find #"environment" endpoint)))]

  (testing (str "endpoint " endpoint)
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
                  "uptime_seconds" "6000"}
          facts3 {"domain" "testing.com"
                  "hostname" "foo3"
                  "kernel" "Darwin"
                  "operatingsystem" "Darwin"}
          facts4 {"domain" "testing.com"
                  "hostname" "foo4"
                  "kernel" "Linux"
                  "operatingsystem" "RedHat"
                  "uptime_seconds" "6000"}]
      (with-transacted-connection *db*
        (scf-store/add-certname! "foo1")
        (scf-store/add-certname! "foo2")
        (scf-store/add-certname! "foo3")
        (scf-store/add-certname! "foo4")
        (scf-store/add-facts! {:name "foo1"
                               :values facts1
                               :timestamp (now)
                               :environment "DEV"
                               :producer-timestamp nil})
        (scf-store/add-facts! {:name "foo2"
                               :values facts2
                               :timestamp (now)
                               :environment "DEV"
                               :producer-timestamp nil})
        (scf-store/add-facts! {:name "foo3"
                               :values facts3
                               :timestamp (now)
                               :environment "PROD"
                               :producer-timestamp nil})
        (scf-store/add-facts! {:name "foo4"
                               :values facts4
                               :timestamp (now)
                               :environment "PROD"
                               :producer-timestamp nil}))

      (doseq [query '[[= environment PROD]
                      [not [= environment DEV]]
                      ["~" environment PR.*]
                      [not ["~" environment DE.*]]]]
        (let [{:keys [status headers body]} (*app* (get-request endpoint query))
              results (json/parse-string (slurp body) true)]
          (is (= status http/status-ok))
          (is (= (headers "Content-Type") c-t))
          (is (= 9 (count results)))
          (is (every? #(= (:environment %) "PROD") results))
          (is (= #{"foo3" "foo4"} (set (map :certname results)))))))))

(deftest environment-query-failures
  (let [{:keys [status headers body]} (*app* (get-request v2-facts-endpoint '[= environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a queryable object for version 2" body)))
  (let [{:keys [status headers body]} (*app* (get-request v3-facts-endpoint '[= environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a queryable object for version 3" body)))
  (let [{:keys [status headers body]} (*app* (get-request v2-facts-endpoint '["~" environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a valid version 2 operand" body)))
  (let [{:keys [status headers body]} (*app* (get-request v3-facts-endpoint '["~" environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a valid version 3 operand" body))))

(defn populate-for-structured-tests
  "Populate the database with tests suitable for structured fact testing"
  [test-time]
  (let [facts1 {"my_structured_fact" {"a" 1
                                      "b" 3.14
                                      "c" ["a" "b" "c"]
                                      "d" {"n" ""}
                                      "e" "1"
                                      "f" nil}
                "domain" "testing.com"
                "uptime_seconds" "4000"
                "test#~delimiter" "foo"}
        facts2 {"my_structured_fact" {"a" 1
                                      "b" 3.14
                                      "c" ["a" "b" "c"]
                                      "d" {"n" ""}
                                      "e" "1"}
                "domain" "testing.com"
                "uptime_seconds" "6000"}
        facts3 {"my_structured_fact" {"a" 1
                                      "b" 3.14
                                      "c" ["a" "b" "c"]
                                      "d" {"n" ""}
                                      "e" "1"
                                      "f" nil
                                      "" "g?"}
                "domain" "testing.com"
                "operatingsystem" "Darwin"}
        facts4 {"my_structured_fact" {"a" 1
                                      "b" 2.71
                                      "c" ["a" "b" "c"]
                                      "d" {"n" ""}
                                      "e" "1"}
                "domain" "testing.com"
                "hostname" "foo4"
                "uptime_seconds" "6000"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:name "foo1"
                             :values facts1
                             :timestamp test-time
                             :environment "DEV"
                             :producer-timestamp test-time})
      (scf-store/add-facts! {:name  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer-timestamp (to-timestamp "2013-01-01")})
      (scf-store/add-facts! {:name "foo3"
                             :values facts3
                             :timestamp test-time
                             :environment "PROD"
                             :producer-timestamp test-time})
      (scf-store/add-facts! {:name "foo4"
                             :values facts4
                             :timestamp test-time
                             :environment "PROD"
                             :producer-timestamp test-time})
      (scf-store/deactivate-node! "foo4"))))

(defn factset-results
  [current-time]
  (map #(utils/assoc-when % "timestamp" (to-string current-time) "producer-timestamp" (to-string current-time))
       [{"facts" {"domain" "testing.com", "uptime_seconds" "4000", "test#~delimiter" "foo",
                  "my_structured_fact" {"d" {"n" ""}, "e" "1", "c" ["a" "b" "c"],
                                        "f" nil, "b" 3.14, "a" 1}},
         "environment" "DEV","certname" "foo1"}
        {"facts" {"uptime_seconds" "6000", "domain" "testing.com",
                  "my_structured_fact" {"d" {"n" ""}, "b" 3.14, "c" ["a" "b" "c"],
                                        "a" 1, "e" "1"}},
         "timestamp" "2013-01-01T00:00:00.000Z", "environment" "DEV", "certname" "foo2"
         "producer-timestamp" "2013-01-01T00:00:00.000Z"}
        {"facts" {"domain" "testing.com", "operatingsystem" "Darwin",
                  "my_structured_fact" {"e" "1", "b" 3.14, "f" nil, "a" 1,
                                        "d" {"n" ""}, "" "g?", "c" ["a" "b" "c"]}},
         "environment" "PROD", "certname" "foo3"}]))

(deftestseq factset-paging-results
  [[version endpoint] factsets-endpoints]
  (let [factset-count 3
        current-time (now)
        expected-results (factset-results current-time)]
    (populate-for-structured-tests current-time)
    (testing "include total results count"
      (let [actual (json/parse-string
                     (slurp (:body (get-response endpoint nil {:include-total true}))))]
        (is (= (count actual) factset-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 factset-count]]]
        (let [results (query-endpoint endpoint {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order-by.*"
                        (:body (*app*
                                 (get-request endpoint nil
                                              {:order-by (json/generate-string
                                                           [{"field" "invalid-field"
                                                             "order" "ASC"}])}))))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" (sort-by #(get % "certname") expected-results)]
                                  ["DESC" (reverse (sort-by #(get % "certname") expected-results))]]]
          (testing order
            (let [ordering {:order-by (json/generate-string [{"field" "certname" "order" order}])}
                  actual (json/parse-string (slurp (:body (get-response endpoint nil ordering))))]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[env-order certname-order] expected-order] [[["DESC" "ASC"]  [2 0 1]]
                                                             [["DESC" "DESC"] [2 1 0]]
                                                             [["ASC" "DESC"]  [1 0 2]]
                                                             [["ASC" "ASC"]   [0 1 2]]]]
          (testing (format "environment %s certname %s" env-order certname-order)
            (let [params {:order-by
                          (json/generate-string [{"field" "environment" "order" env-order}
                                                 {"field" "certname" "order" certname-order}])}
                  actual (json/parse-string (slurp (:body (get-response endpoint nil params))))]
              (is (= actual (map #(nth expected-results %) expected-order))))))
        (doseq [[[pt-order certname-order] expected-order] [[["DESC" "ASC"]  [0 2 1]]
                                                             [["DESC" "DESC"] [2 0 1]]
                                                             [["ASC" "DESC"]  [1 2 0]]
                                                             [["ASC" "ASC"]   [1 0 2]]]]
          (testing (format "producer-timestamp %s certname %s" pt-order certname-order)
            (let [params {:order-by
                          (json/generate-string [{"field" "producer-timestamp" "order" pt-order}
                                                 {"field" "certname" "order" certname-order}])}
                  actual (json/parse-string (slurp (:body (get-response endpoint nil params))))]
              (is (= actual (map #(nth expected-results %) expected-order))))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [0 1 2]]
                                                   [1 [1 2]]
                                                   [2 [2]]
                                                   [3 [ ]]]]
                                          ["DESC" [[0 [2 1 0]]
                                                   [1 [1 0]]
                                                   [2 [0]]
                                                   [3 [ ]]]]]]
        (doseq [[offset expected-order] expected-sequences]
          (let [params {:order-by (json/generate-string [{"field" "certname" "order" order}]) :offset offset}
                actual (json/parse-string (slurp (:body (get-response endpoint nil params))))]
            (is (= actual (map #(nth expected-results %) expected-order)))))))))

(deftestseq factset-queries
  [[version endpoint] factsets-endpoints]
  (let [current-time (now)]
    (populate-for-structured-tests current-time)

    (testing "query without param should not fail"
      (let [response (get-response endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "factsets query should ignore deactivated nodes"
      (let [responses (json/parse-string (slurp (:body (get-response endpoint))))]
        (is (not (contains? (into [] (map #(get % "certname") responses)) "foo4")))))

    (testing "factset queries should return appropriate results"
      (let [queries [["=" "certname" "foo1"]
                     ["=" "environment" "DEV"]
                     ["<" "timestamp" "2014-01-01"]
                     ["<" "producer-timestamp" "2014-01-01"]]
            responses (map (comp json/parse-string
                                 slurp
                                 :body
                                 (partial get-response endpoint)) queries)]
        (is (= (into {} (first responses))
               {"facts" {"my_structured_fact"
                         {"a" 1
                          "b" 3.14
                          "c" ["a" "b" "c"]
                          "d" {"n" ""}
                          "e" "1"
                          "f" nil}
                         "domain" "testing.com"
                         "uptime_seconds" "4000"
                         "test#~delimiter" "foo"}
                "timestamp" (to-string current-time)
                "producer-timestamp" (to-string current-time)
                "environment" "DEV"
                "certname" "foo1"}))
        (is (= (into [] (nth responses 1))
               [{"facts" {"my_structured_fact"
                          {"a" 1
                           "b" 3.14
                           "c" ["a" "b" "c"]
                           "d" {"n" ""}
                           "e" "1"
                           "f" nil}
                          "domain" "testing.com"
                          "uptime_seconds" "4000"
                          "test#~delimiter" "foo"}
                 "timestamp" (to-string current-time)
                "producer-timestamp" (to-string current-time)
                 "environment" "DEV"
                 "certname" "foo1"}

                {"facts" {"my_structured_fact"
                          {"a" 1
                           "b" 3.14
                           "c" ["a" "b" "c"]
                           "d" {"n" ""}
                           "e" "1"}
                          "domain" "testing.com"
                          "uptime_seconds" "6000"}
                 "timestamp" (to-string (to-timestamp "2013-01-01"))
                 "producer-timestamp" (to-string (to-timestamp "2013-01-01"))
                 "environment" "DEV"
                 "certname" "foo2"}]))
        (is (= (into [] (nth responses 2))
               [{"facts" {"my_structured_fact"
                          {"a" 1
                           "b" 3.14
                           "c" ["a" "b" "c"]
                           "d" {"n" ""}
                           "e" "1"}
                          "domain" "testing.com"
                          "uptime_seconds" "6000"}
                 "timestamp" (to-string (to-timestamp "2013-01-01"))
                 "producer-timestamp" (to-string (to-timestamp "2013-01-01"))
                 "environment" "DEV"
                 "certname" "foo2"}]))
        (is (= (into [] (nth responses 3))
               [{"facts" {"my_structured_fact"
                          {"a" 1
                           "b" 3.14
                           "c" ["a" "b" "c"]
                           "d" {"n" ""}
                           "e" "1"}
                          "domain" "testing.com"
                          "uptime_seconds" "6000"}
                 "timestamp" (to-string (to-timestamp "2013-01-01"))
                 "producer-timestamp" (to-string (to-timestamp "2013-01-01"))
                 "environment" "DEV"
                 "certname" "foo2"}]))))))

(defn structured-fact-results
  [version endpoint]
  (case [version endpoint]
    [:v4 "/v4/environments/DEV/facts"]
    {["=" "certname" "foo1"]
     [{:value "testing.com" :name "domain" :environment "DEV" :certname "foo1"}
      {:value {:b 3.14 :a 1 :e "1" :d {:n ""} :c ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo1"}
      {:value "foo" :name "test#~delimiter" :environment "DEV" :certname "foo1"}
      {:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}]
     ["=" "value" 3.14] ()
     ["<=" "value" 10] ()
     [">=" "value" 10]
     [{:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}
      {:value 6000 :name "uptime_seconds" :environment "DEV" :certname "foo2"}]
     ["<" "value" 10] ()
     [">" "value" 10]
     [{:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}
      {:value 6000 :name "uptime_seconds" :environment "DEV" :certname "foo2"}]
     ["=" "name" "my_structured_fact"]
     [{:value  {:b 3.14 :a 1 :e "1" :d  {:n ""} :c  ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo1"}
      {:value  {:d  {:n ""} :b 3.14 :a 1 :e "1" :c  ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo2"}]}

    [:v4 "/v4/facts"]
    {["=" "certname" "foo1"]
     [{:value "testing.com" :name "domain" :environment "DEV" :certname "foo1"}
      {:value {:b 3.14 :a 1 :e "1" :d {:n ""} :c ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo1"}
      {:value "foo" :name "test#~delimiter" :environment "DEV" :certname "foo1"}
      {:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}]
     ["=" "value" 3.14] ()
     ["<=" "value" 10] ()
     [">=" "value" 10]
     [{:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}
      {:value 6000 :name "uptime_seconds" :environment "DEV" :certname "foo2"}]
     ["<" "value" 10] ()
     [">" "value" 10]
     [{:value 4000 :name "uptime_seconds" :environment "DEV" :certname "foo1"}
      {:value 6000 :name "uptime_seconds" :environment "DEV" :certname "foo2"}]
     ["=" "name" "my_structured_fact"]
     [{:value {:b 3.14 :a 1 :e "1" :d {:n ""} :c ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo1"}
      {:value {:d {:n ""} :b 3.14 :a 1 :e "1" :c ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo2"}
      {:value {:b 3.14 :a 1 :d {:n ""} :c ["a" "b" "c"] :e "1"} :name "my_structured_fact" :environment "PROD" :certname "foo3"}]}

    {["=" "certname" "foo1"]
     [{:value "testing.com" :name "domain" :certname "foo1"}
      {:value "{\"b\":3.14,\"a\":1,\"e\":\"1\",\"d\":{\"n\":\"\"},\"c\":[\"a\",\"b\",\"c\"]}" :name "my_structured_fact" :certname "foo1"}
      {:value "foo" :name "test#~delimiter" :certname "foo1"}
      {:value "4000" :name "uptime_seconds" :certname "foo1"}]
     ["=" "value" 3.14] ()
     ["<=" "value" 10] ()
     [">=" "value" 10]
     [{:value "4000" :name "uptime_seconds" :certname "foo1"}
      {:value "6000" :name "uptime_seconds" :certname "foo2"}]
     ["<" "value" 10] ()
     [">" "value" 10]
     [{:value "4000" :name "uptime_seconds" :certname "foo1"}
      {:value "6000" :name "uptime_seconds" :certname "foo2"}]
     ["=" "name" "my_structured_fact"]
     [{:value "{\"b\":3.14,\"a\":1,\"e\":\"1\",\"d\":{\"n\":\"\"},\"c\":[\"a\",\"b\",\"c\"]}"
       :name "my_structured_fact"
       :certname "foo1"}
      {:value "{\"d\":{\"n\":\"\"},\"b\":3.14,\"a\":1,\"e\":\"1\",\"c\":[\"a\",\"b\",\"c\"]}" :name "my_structured_fact" :certname "foo2"}
      {:value "{\"b\":3.14,\"a\":1,\"d\":{\"n\":\"\"},\"c\":[\"a\",\"b\",\"c\"],\"e\":\"1\"}" :name "my_structured_fact" :certname "foo3"}]}))

(deftestseq structured-fact-queries
  [[version endpoint] facts-endpoints]
  ( let [current-time (now)
         facts1 {"my_structured_fact" {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"
                                       }
                 "domain" "testing.com"
                 "uptime_seconds" 4000
                 "test#~delimiter" "foo"}
         facts2 {"my_structured_fact" {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"
                                       }
                 "domain" "testing.com"
                 "uptime_seconds" 6000}
         facts3 {"my_structured_fact" {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"
                                       }
                 "domain" "testing.com"
                 "operatingsystem" "Darwin"}
         facts4 {"my_structured_fact" {"a" 1
                                       "b" 2.71
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"
                                       }
                 "domain" "testing.com"
                 "hostname" "foo4"
                 "uptime_seconds" 6000}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:name "foo1"
                             :values facts1
                             :timestamp current-time
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo3"
                             :values facts3
                             :timestamp current-time
                             :environment "PROD"
                             :producer-timestamp nil})
      (scf-store/add-facts! {:name "foo4"
                             :values facts4
                             :timestamp current-time
                             :environment "PROD"
                             :producer-timestamp nil})
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (get-response endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact queries should return appropriate results"
      (let [queries [["=" "certname" "foo1"]
                     ["=" "value" 3.14]
                     ["<=" "value" 10]
                     [">=" "value" 10]
                     ["<" "value" 10]
                     [">" "value" 10]
                     ["=" "name" "my_structured_fact"]]
            responses (map (comp parse-result
                                 :body
                                 (partial get-response endpoint)) queries)]

        (doseq [[response query] (map vector responses queries)]
          (compare-structured-response
           (sort-by (juxt :certname :name) response)
           (sort-by (juxt :certname :name) (get (structured-fact-results version endpoint) query))
           version))))))

(deftestseq fact-contents-queries
  [[version endpoint] fact-contents-endpoints]
  (let [current-time (now)]
    (populate-for-structured-tests current-time)

    (testing "query without param should not fail"
      (let [response (get-response endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact nodes queries should ignore deactivated nodes"
      (let [responses (json/parse-string (slurp (:body (get-response endpoint))))]
        (is (not (contains? (into [] (map #(get % "certname") responses)) "foo4")))))

    (testing "fact nodes queries should return appropriate results"
      (let [response (comp json/parse-string
                           slurp
                           :body
                           #(get-response endpoint % {:order-by (json/generate-string [{:field "path"} {:field "certname"}])}))]
        (is (= (into {} (first (response ["=" "certname" "foo1"])))
               {"certname" "foo1", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "DEV"}))
        (is (= (into [] (response ["=" "environment" "DEV"]))
               [{"certname" "foo1", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "DEV"}
                {"certname" "foo2", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 0], "value" "a", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 0], "value" "a", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 1], "value" "b", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 1], "value" "b", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 2], "value" "c", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 2], "value" "c", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "d" "n"], "value" "", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "d" "n"], "value" "", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "e"], "value" "1", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "e"], "value" "1", "environment" "DEV"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "f"], "value" nil, "environment" "DEV"}
                {"certname" "foo1", "name" "test#~delimiter" "path" ["test#~delimiter"], "value" "foo", "environment" "DEV"}
                {"certname" "foo1", "name" "uptime_seconds" "path" ["uptime_seconds"], "value" "4000", "environment" "DEV"}
                {"certname" "foo2", "name" "uptime_seconds" "path" ["uptime_seconds"], "value" "6000", "environment" "DEV"}]))
        (is (= (into [] (response ["=" "path" ["my_structured_fact" "c" 2]]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 2], "value" "c", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 2], "value" "c", "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 2], "value" "c", "environment" "PROD"}]))
        (is (= (into [] (response ["~>" "path" ["my_structured_fact" "f"]]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "f"], "value" nil, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "f"], "value" nil, "environment" "PROD"}]))
        (is (= (into [] (response ["~>" "path" [".*structured.*" "a"]]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "PROD"}]))
        (is (= (into [] (response ["~>" "path" [".+structured.+" "a"]]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "PROD"}]))
        (is (= (into [] (response ["~>" "path" ["my_structured_fact" "[a-b]"]]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "PROD"}
                {"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "PROD"}]))
        (is (= (into [] (response ["~>" "path" ["my_structured_fact"]]))
               []))
        (is (= (into [] (response ["~>" "path" ["my_structured_fact" "^a"]]))
               []))
        (is (= (into [] (response ["and" ["~>" "path" ["my_structured_fact" ".*"]] ["=" "certname" "foo1"]]))
               [{"certname" "foo1", "path" ["my_structured_fact" "a"], "name" "my_structured_fact", "value" 1, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "b"], "name" "my_structured_fact", "value" 3.14, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "e"], "name" "my_structured_fact", "value" "1", "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "f"], "name" "my_structured_fact", "value" nil, "environment" "DEV"}]))
        (is (= (into [] (response ["and" ["~>" "path" ["my_structured_fact" ".+"]] ["=" "certname" "foo1"]]))
               [{"certname" "foo1", "path" ["my_structured_fact" "a"], "name" "my_structured_fact", "value" 1, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "b"], "name" "my_structured_fact", "value" 3.14, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "e"], "name" "my_structured_fact", "value" "1", "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "f"], "name" "my_structured_fact", "value" nil, "environment" "DEV"}]))
        (is (= (into [] (response ["and" ["~>" "path" ["my_structured_fact" ".{1,20}"]] ["=" "certname" "foo1"]]))
               [{"certname" "foo1", "path" ["my_structured_fact" "a"], "name" "my_structured_fact", "value" 1, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "b"], "name" "my_structured_fact", "value" 3.14, "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "e"], "name" "my_structured_fact", "value" "1", "environment" "DEV"}
                {"certname" "foo1", "path" ["my_structured_fact" "f"], "name" "my_structured_fact", "value" nil, "environment" "DEV"}]))
        (is (= (into [] (response ["and" ["~>" "path" ["my_structured_fact" "c" 1]] ["=" "certname" "foo2"]]))
               [{"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 1], "value" "b", "environment" "DEV"}]))
        (is (= (into [] (response ["=" "value" "a"]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 0], "value" "a", "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 0], "value" "a", "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "c" 0], "value" "a", "environment" "PROD"}]))
        (is (= (into [] (response ["=" "value" 3.14]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "PROD"}]))
        (is (= (into [] (response [">" "value" 3.1]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "b"], "value" 3.14, "environment" "PROD"}]))
        (is (= (into [] (response ["~" "value" "testing"]))
               [{"certname" "foo1", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "DEV"}
                {"certname" "foo2", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "DEV"}
                {"certname" "foo3", "name" "domain" "path" ["domain"], "value" "testing.com", "environment" "PROD"}]))
        (is (= (into [] (response ["=" "value" nil]))
               [{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "f"], "value" nil, "environment" "DEV"}
                {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "f"], "value" nil, "environment" "PROD"}]))
        (is (= (into [] (response ["~" "name" "#~"]))
               [{"certname" "foo1", "path" ["test#~delimiter"], "name" "test#~delimiter", "value" "foo", "environment" "DEV"}]))
        (is (= (into [] (response ["=", "name" "domain"]))
               [{"certname" "foo1", "path" ["domain"], "name" "domain", "value" "testing.com", "environment" "DEV"}
                {"certname" "foo2", "path" ["domain"], "name" "domain", "value" "testing.com", "environment" "DEV"}
                {"certname" "foo3", "path" ["domain"], "name" "domain", "value" "testing.com", "environment" "PROD"}]))))))
