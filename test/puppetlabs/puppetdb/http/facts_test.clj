(ns puppetlabs.puppetdb.http.facts-test
  (:require [cheshire.core :as json]
            [clj-time.coerce :refer [to-timestamp to-string]]
            [clj-time.core :refer [now]]
            [clojure.java.jdbc.deprecated :as sql]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.testutils :refer [get-request
                                                   assert-success!
                                                   paged-results
                                                   paged-results*
                                                   deftestseq
                                                   create-hsqldb-map
                                                   parse-result]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response
                                                        vector-param]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.middleware :as mid]))

(def v4-facts-endpoint "/v4/facts")
(def v4-facts-environment "/v4/environments/DEV/facts")
(def facts-endpoints [[:v4 v4-facts-endpoint]
                      [:v4 v4-facts-environment]])

(def factsets-endpoints [[:v4 "/v4/factsets"]])

(def fact-contents-endpoints [[:v4 "/v4/fact-contents"]])

(use-fixtures :each with-test-db with-http-app)

(def c-t http/json-response-content-type)
(def reference-time "2014-10-28T20:26:21.727Z")

(defn is-query-result
  [endpoint query expected-results]
  (let [request (get-request endpoint (json/generate-string query))
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count actual-result) (count expected-results)))
    (is (= (set actual-result) expected-results))
    (is (= status http/status-ok))))

(defn compare-structured-response
  "compare maps that may have been stringified differently."
  [response expected version]
  (is (= response expected)))

(def common-subquery-tests
  (omap/ordered-map
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_resources"
                                            ["and"
                                             ["=" "type" "Class"]
                                             ["=" "title" "Apache"]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}}

   ;; "not" matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["not"
     ["in" "certname" ["extract" "certname" ["select_resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Apache"]]]]]]]

   #{{:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; Multiple matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_resources"
                                            ["=" "type" "Class"]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; Multiple facts
   ["and"
    ["or"
     ["=" "name" "ipaddress"]
     ["=" "name" "operatingsystem"]]
    ["in" "certname" ["extract" "certname" ["select_resources"
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
     ["in" "certname" ["extract" "certname" ["select_resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Apache"]]]]]
     ["in" "certname" ["extract" "certname" ["select_resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Main"]]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}

   ;; No matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_resources"
                                            ["=" "type" "NotRealAtAll"]]]]]
   #{}

   ;; No matching facts
   ["and"
    ["=" "name" "nosuchfact"]
    ["in" "certname" ["extract" "certname" ["select_resources"
                                            ["=" "type" "Class"]]]]]
   #{}

   ;; Fact subquery
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101" :environment "DEV"}}

   ;; Using a different column
   ["in" "name" ["extract" "name" ["select_facts"
                                   ["=" "name" "osfamily"]]]]

   #{{:certname "bar" :name "osfamily" :value "Debian" :environment "DEV"}
     {:certname "baz" :name "osfamily" :value "RedHat" :environment "DEV"}
     {:certname "foo" :name "osfamily" :value "Debian" :environment "DEV"}}

   ;; Nested fact subqueries
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]
                                             ["in" "certname" ["extract" "certname" ["select_facts"
                                                                                     ["and"
                                                                                      ["=" "name" "uptime_seconds"]
                                                                                      [">" "value" 10000]]]]]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}}
   ;; Multiple fact subqueries
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select_facts"
                                            ["and"
                                             ["=" "name" "osfamily"]
                                             ["=" "value" "Debian"]]]]]
    ["in" "certname" ["extract" "certname" ["select_facts"
                                            ["and"
                                             ["=" "name" "uptime_seconds"]
                                             [">" "value" 10000]]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}}))

(def versioned-subqueries
  (omap/ordered-map
   "/v4/facts"
   (merge common-subquery-tests
          (omap/ordered-map
           ;; vectored fact-contents subquery
           ["in" ["name" "certname"]
            ["extract" ["name" "certname"]
             ["select_fact_contents"
              ["and" ["<" "value" 10000] ["~>" "path" ["up.*"]]]]]]
           #{{:value 12, :name "uptime_seconds", :environment "DEV", :certname "bar"}}))))

(def versioned-invalid-subqueries
  (omap/ordered-map
   "/v4/facts" (omap/ordered-map
                ;; Extract using invalid fields should throw an error
                ["in" "certname" ["extract" "nothing" ["select_resources"
                                                        ["=" "type" "Class"]]]]
                "Can't extract unknown 'resources' field 'nothing'. Acceptable fields are: [\"certname\",\"environment\",\"exported\",\"file\",\"line\",\"parameters\",\"resource\",\"tag\",\"tags\",\"title\",\"type\"]"

                ["in" "certname" ["extract" ["nothing" "nothing2" "certname"] ["select_resources"
                                                                               ["=" "type" "Class"]]]]
                "Can't extract unknown 'resources' fields: 'nothing', 'nothing2'. Acceptable fields are: [\"certname\",\"environment\",\"exported\",\"file\",\"line\",\"parameters\",\"resource\",\"tag\",\"tags\",\"title\",\"type\"]"

                ;; In-query for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select_resources"
                                                        ["=" "type" "Class"]]]]
                "Can't match on unknown 'facts' field 'nothing' for 'in'. Acceptable fields are: [\"certname\",\"environment\",\"name\",\"value\"]"

                ["in" ["name" "nothing" "nothing2"] ["extract" "certname" ["select_resources"
                                                                            ["=" "type" "Class"]]]]
                "Can't match on unknown 'facts' fields: 'nothing', 'nothing2' for 'in'. Acceptable fields are: [\"certname\",\"environment\",\"name\",\"value\"]")))

(def versioned-invalid-queries
  (omap/ordered-map
   "/v4/facts" (omap/ordered-map
                 ;; comparison applied to a string should throw an error
                 ["<" "value" "100"]
                 #"Argument \"100\" and operator \"<\" have incompatible types."
                ;; Top level extract using invalid fields should throw an error
                ["extract" "nothing" ["~" "certname" ".*"]]
                #"Can't extract unknown 'facts' field 'nothing'.*Acceptable fields are.*"

                ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
                #"Can't extract unknown 'facts' fields: 'nothing', 'nothing2'.*Acceptable fields are.*")))

(deftestseq invalid-projections
  [[version endpoint] facts-endpoints
   method [:get :post]]

  (doseq [[query msg] (get versioned-invalid-queries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/facts" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftestseq ^{:hsqldb false} pg-invalid-regexps
  [[version endpoint] facts-endpoints
   method [:get :post]]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

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

   ;; Verify that we can explicitly ask for inactive nodes
   ["=" ["node" "active"] false]
   [{:certname "foo4", :environment "DEV", :name "operatingsystem", :value "RedHat"}
    {:certname "foo4", :environment "DEV", :name "kernel", :value "Linux"}
    {:certname "foo4", :environment "DEV", :name "domain", :value "testing.com"}
    {:certname "foo4", :environment "DEV", :name "uptime_seconds", :value 6000}
    {:certname "foo4", :environment "DEV", :name "hostname", :value "foo4"}]

   ;; ...and for all nodes, regardless of active/inactive status
   ["or" ["=" ["node" "active"] true]
         ["=" ["node" "active"] false]]
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
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}
    {:certname "foo4", :environment "DEV", :name "operatingsystem", :value "RedHat"}
    {:certname "foo4", :environment "DEV", :name "kernel", :value "Linux"}
    {:certname "foo4", :environment "DEV", :name "domain", :value "testing.com"}
    {:certname "foo4", :environment "DEV", :name "uptime_seconds", :value 6000}
    {:certname "foo4", :environment "DEV", :name "hostname", :value "foo4"}]))


(defn versioned-well-formed-tests
  [version]
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

          ["extract" "certname"
           ["not" ["=" "name" "domain"]]]
          [{:certname "foo1"}
           {:certname "foo1"}
           {:certname "foo1"}
           {:certname "foo1"}
           {:certname "foo1"}
           {:certname "foo2"}
           {:certname "foo1"}
           {:certname "foo2"}
           {:certname "foo2"}
           {:certname "foo2"}
           {:certname "foo3"}
           {:certname "foo3"}
           {:certname "foo3"}]

          ["extract" ["certname" "name"]
           ["not" ["=" "name" "domain"]]]
          [{:certname "foo1" :name "hostname"}
           {:certname "foo1" :name "kernel"}
           {:certname "foo1" :name "operatingsystem"}
           {:certname "foo1" :name "some_version"}
           {:certname "foo1" :name "uptime_seconds"}
           {:certname "foo2" :name "hostname"}
           {:certname "foo1" :name "bigstr"}
           {:certname "foo2" :name "kernel"}
           {:certname "foo2" :name "operatingsystem"}
           {:certname "foo2" :name "uptime_seconds"}
           {:certname "foo3" :name "hostname"}
           {:certname "foo3" :name "kernel"}
           {:certname "foo3" :name "operatingsystem"}]

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
           {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}])))

(defn test-app
  ([read-write-db]
   (test-app read-write-db read-write-db))
  ([read-db write-db]
   (mid/wrap-with-puppetdb-middleware
    (server/build-app #(hash-map :scf-read-db read-db
                                 :scf-write-db write-db
                                 :command-mq *mq*
                                 :product-name "puppetdb"))
    nil)))

(defn with-shutdown-after [dbs f]
  (f)
  (doseq [db dbs]
    (sql/with-connection db
      (sql/do-commands "SHUTDOWN"))
    (.close (:datasource db))))

(defmacro with-shutdown-after
  [dbs & body]
  `(do ~@body)
  `(doseq [db# ~dbs]
     (sql/with-connection db#
       (sql/do-commands "SHUTDOWN"))
     (.close (:datasource db#))))

(deftestseq fact-queries
  [[version endpoint] facts-endpoints
   method [:get :post]]

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
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (query-response method endpoint)]
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
              (is (= (set result)
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
  [[version endpoint] facts-endpoints
   method [:get :post]]

  (scf-store/add-certname! "foo")
  (scf-store/add-certname! "bar")
  (scf-store/add-certname! "baz")
  (scf-store/add-facts! {:certname "foo"
                         :values {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)})
  (scf-store/add-facts! {:certname "bar"
                         :values {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)})
  (scf-store/add-facts! {:certname "baz"
                         :values {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)})

  (let [catalog (:empty catalogs)
        apache-resource {:type "Class" :title "Apache"}
        apache-catalog (update-in catalog [:resources] conj {apache-resource (assoc apache-resource :exported false)})]
    (scf-store/replace-catalog! (assoc apache-catalog :certname "foo") (now))
    (scf-store/replace-catalog! (assoc apache-catalog :certname "bar") (now))
    (scf-store/replace-catalog! (assoc catalog :certname "baz") (now)))

  (doseq [[query results] (get versioned-subqueries endpoint)]
    (testing (str "query: " query " should match expected output")
      (is-query-result endpoint query (set results))))

  (testing "subqueries: invalid"
    (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
      (testing (str "query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body] :as result} (*app* request)]
          (is (= body msg))
          (is (= status http/status-bad-request)))))))

(deftestseq ^{:postgres false} two-database-fact-query-config
  [[version endpoint] facts-endpoints
   method [:get :post]]

  (let [read-db-config (create-hsqldb-map)
        write-db-config (create-hsqldb-map)
        config (-> (svc-utils/create-config)
                   (assoc :read-database read-db-config)
                   (assoc :database write-db-config))
        read-db (-> read-db-config
                    defaulted-read-db-config
                    (init-db true))
        write-db (-> write-db-config
                     defaulted-write-db-config
                     (init-db false))]

    (with-shutdown-after [read-db write-db]
        (svc-utils/call-with-puppetdb-instance
          config
          (fn []
            (let [pdb (get-service svc-utils/*server* :PuppetDBServer)
                  shared-globals (cli-svc/shared-globals pdb)
                  read-db (:scf-read-db shared-globals)
                  write-db (:scf-write-db shared-globals)
                  one-db-app (test-app write-db)
                  two-db-app (test-app read-db write-db)
                  facts1 {"domain" "testing.com"
                          "hostname" "foo1"
                          "kernel" "Linux"
                          "operatingsystem" "Debian"
                          "some_version" "1.3.7+build.11.e0f985a"
                          "uptime_seconds" "4000"}]

              (with-transacted-connection write-db
                (scf-store/add-certname! "foo1")
                (scf-store/add-facts! {:certname "foo1"
                                       :values facts1
                                       :timestamp (now)
                                       :environment "DEV"
                                       :producer_timestamp (now)}))

              (testing "queries only use the read database"
                (let [request (get-request endpoint (json/parse-string nil))
                      {:keys [status body headers]} (two-db-app request)]
                  (is (= (headers "Content-Type") c-t))
                  ;; Environments endpoint will return a proper JSON
                  ;; error with a 404, as opposed to an empty array.
                  (if (= endpoint "/v4/environments/DEV/facts")
                    (do
                      (is (= {:error "No information is known about environment DEV"} (json/parse-string body true)))
                      (is (= status http/status-not-found)))
                    (do
                      (is (empty? (json/parse-stream (io/reader body) true)))
                      (is (= status http/status-ok))))))

              (testing "config with only a single database returns results"
                (let [request (get-request endpoint (json/parse-string nil))
                      {:keys [status body headers]} (one-db-app request)]
                  (is (= status http/status-ok))
                  (is (= (headers "Content-Type") c-t))
                  (is (= [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
                          {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
                          {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
                          {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
                          {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
                          {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}]
                         (sort-by :name (json/parse-stream (io/reader body) true))))))))))))

(defn test-paged-results
  [endpoint query limit total include_total]
  (paged-results
   {:app-fn  *app*
    :path    endpoint
    :query   query
    :limit   limit
    :total   total
    :include_total include_total}))

(deftestseq fact-query-paging
  [[version endpoint] facts-endpoints
   method [:get :post]]

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
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)})
      (scf-store/add-facts! {:certname "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)}))

    (testing "should support fact paging"
      (doseq [[label counts?] [["without" false]
                               ["with" true]]]
        (testing (str "should support paging through facts " label " counts")
          (let [results (test-paged-results endpoint
                                            ["=" "certname" "foo1"]
                                            2 (count facts1) counts?)]
            (is (= (count facts1) (count results)))
            (is (= (set (map (fn [[k v]]
                               {:certname "foo1"
                                :environment "DEV"
                                :name     k
                                :value    v})
                             facts1))
                   (set results)))))))))

(defn- raw-query-endpoint
  [endpoint query paging-options]
  (let [{:keys [limit offset include_total]
         :or {limit Integer/MAX_VALUE
              include_total true
              offset 0}}  paging-options
              {:keys [headers body]} (paged-results* (assoc paging-options
                                                       :app-fn *app*
                                                       :query query
                                                       :path endpoint
                                                       :offset offset
                                                       :limit limit
                                                       :include_total include_total))]
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
  ([endpoint paging-options]
   (:results (raw-query-endpoint endpoint nil paging-options)))
  ([endpoint query paging-options]
   (:results (raw-query-endpoint endpoint query paging-options))))

(deftestseq paging-results
  [[version endpoint] facts-endpoints
   method [:get :post]]

  (let [f1 {:certname "a.local" :name "hostname"    :value "a-host" :environment "DEV"}
        f2 {:certname "b.local" :name "uptime_days" :value "4" :environment "DEV"}
        f3 {:certname "c.local" :name "hostname"    :value "c-host" :environment "DEV"}
        f4 {:certname "d.local" :name "uptime_days" :value "2" :environment "DEV"}
        f5 {:certname "e.local" :name "my_structured_fact"
            :value {"a" [1 2 3 4 5 6 7 8 9 10]} :environment "DEV"}
        fact-count 5]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! {:certname "c.local"
                           :values {"hostname" "c-host"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:certname "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:certname "d.local"
                           :values {"uptime_days" "2"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:certname "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "e.local")
    (scf-store/add-facts! {:certname "e.local"
                           :values {"my_structured_fact" (:value f5)}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)})

    (testing "include total results count"
      (let [actual (:count (raw-query-endpoint endpoint nil {:include_total true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-endpoint endpoint {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order_by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order_by.*"
                        (:body (query-response method endpoint nil
                                               {:order_by (vector-param method
                                                                       [{"field" "invalid-field"
                                                                         "order" "ASC"}])})))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4 f5]]
                                  ["DESC" [f5 f4 f3 f2 f1]]]]
          (testing order
            (let [actual (->> {:order_by (vector-param method [{"field" "certname" "order" order}])}
                              (query-response method endpoint nil)
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (compare-structured-response (map unkeywordize-values actual)
                                           expected
                                           version)))))

      (testing "unextracted field with alias"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4 f5]]
                                  ["DESC" [f5 f4 f3 f2 f1]]]]
          (testing order
            (let [actual (->> {:order_by (vector-param method [{"field" "certname" "order" order}])}
                              (query-response method endpoint ["extract" "environment" ["~" "certname" ".*"]])
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (compare-structured-response
                (map (comp :environment unkeywordize-values) actual)
                (map :environment expected)
                version)))))

      (testing "multiple fields"
        (doseq [[[name-order certname-order] expected] [[["DESC" "ASC"]  [f2 f4 f5 f1 f3]]
                                                        [["DESC" "DESC"] [f4 f2 f5 f3 f1]]
                                                        [["ASC" "DESC"]  [f3 f1 f5 f4 f2]]
                                                        [["ASC" "ASC"]   [f1 f3 f5 f2 f4]]]]
          (testing (format "name %s certname %s" name-order certname-order)
            (let [actual (->> {:order_by (vector-param method [{"field" "name" "order" name-order}
                                                              {"field" "certname" "order" certname-order}])}
                              (query-response method endpoint nil)
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (compare-structured-response (map unkeywordize-values actual)
                                           expected
                                           version))))))

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
            (let [actual (->> {:order_by (vector-param method [{"field" "certname" "order" order}]) :offset offset}
                              (query-response method endpoint nil)
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (compare-structured-response (map unkeywordize-values actual)
                                           expected
                                           version))))
        (testing "rejects order by value on v4+"
          (is (re-matches #"Unrecognized column 'value' specified in :order_by.*"
                          (:body (query-response method endpoint nil
                                                 {:order_by
                                                  (vector-param method
                                                               [{"field" "value" "order" "ASC"}])})))))))))

(deftestseq facts-environment-paging
  [[version endpoint] facts-endpoints
   method [:get :post]
   :when (not= endpoint v4-facts-environment)]

  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host" :environment "A"}
        f2         {:certname "b.local" :name "uptime_days" :value "4" :environment "B"}
        f3         {:certname "c.local" :name "my_structured_fact"
                    :value {"a" [1 2 3 4 5 6 7 8 9 10]} :environment "C"}
        f4         {:certname "b2.local" :name "max" :value "4" :environment "B"}
        f5         {:certname "d.local" :name "min" :value "-4" :environment "D"}]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! {:certname "c.local"
                           :values {"my_structured_fact" (:value f3)}
                           :timestamp (now)
                           :environment "C"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:certname "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "A"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:certname "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "b2.local")
    (scf-store/add-facts! {:certname "b2.local"
                           :values {"max" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer_timestamp (now)})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:certname "d.local"
                           :values {"min" "-4"}
                           :timestamp (now)
                           :environment "D"
                           :producer_timestamp (now)})

    (testing "ordering by environment should work"
      (doseq [[[env-order name-order] expected] [[["DESC" "ASC"]  [f5 f3 f4 f2 f1]]
                                                 [["DESC" "DESC"]   [f5 f3 f2 f4 f1]]
                                                 [["ASC" "DESC"]  [f1 f2 f4 f3 f5]]
                                                 [["ASC" "ASC"]  [f1 f4 f2 f3 f5]]]]

        (testing (format "environment %s name %s" env-order name-order)
          (let [actual (query-response method
                                       endpoint
                                       nil
                                       {:order_by
                                                 (vector-param method [{"field" "environment" "order" env-order}
                                                                      {"field" "name" "order" name-order}])})]
            (compare-structured-response (map unkeywordize-values (json/parse-string (slurp (:body actual)) true))
                                         expected
                                         version)))))))

(deftestseq fact-environment-queries
  [[version endpoint] facts-endpoints
   method [:get :post]
   :when (not #(re-find #"environment" endpoint))]

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
        (scf-store/add-facts! {:certname "foo1"
                               :values facts1
                               :timestamp (now)
                               :environment "DEV"
                               :producer_timestamp (now)})
        (scf-store/add-facts! {:certname "foo2"
                               :values facts2
                               :timestamp (now)
                               :environment "DEV"
                               :producer_timestamp (now)})
        (scf-store/add-facts! {:certname "foo3"
                               :values facts3
                               :timestamp (now)
                               :environment "PROD"
                               :producer_timestamp (now)})
        (scf-store/add-facts! {:certname "foo4"
                               :values facts4
                               :timestamp (now)
                               :environment "PROD"
                               :producer_timestamp (now)}))

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
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp test-time
                             :environment "DEV"
                             :producer_timestamp test-time})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer_timestamp (to-timestamp "2013-01-01")})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp test-time
                             :environment "PROD"
                             :producer_timestamp test-time})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp test-time
                             :environment "PROD"
                             :producer_timestamp test-time})
      (scf-store/deactivate-node! "foo4"))))

(def db {:classname "org.postgresql.Driver"
           :subprotocol "postgresql"
           :subname "//localhost:5432/puppetdb"
           :username "puppetdb"
           :password "puppetdb"})

;; FACTSETS TRANSFORMATION

(defn strip-expanded
  "Strips out expanded data from the query results if the database is HSQLDB"
  [{:strs [facts] :as record}]
  (if (sutils/postgres?)
    (update-in record ["facts" "data"] set)
    (assoc record "facts" (dissoc facts "data"))))

(defn munge-factset-response
  [factset]
  (if (sutils/postgres?)
    (update-in factset ["facts" "data"] set)
    factset))

(defn munge-factsets-response
  [factsets]
  (map munge-factset-response
       factsets))

;; FACTSETS TESTS

(defn factset-results
  [version]
  (map (comp strip-expanded
             #(utils/assoc-when % "timestamp" reference-time "producer_timestamp" reference-time))
       [{"facts" {"href" (str "/" (name version) "/factsets/foo1/facts")
                  "data" [{"name" "domain"
                           "value" "testing.com"}
                          {"name" "uptime_seconds"
                           "value" "4000"}
                          {"name" "test#~delimiter"
                           "value" "foo"}
                          {"name" "my_structured_fact"
                           "value" {"d" {"n" ""}, "e" "1", "c" ["a" "b" "c"]
                                    "f" nil, "b" 3.14, "a" 1}}]},
         "environment" "DEV"
         "certname" "foo1"
         "hash" "b966980c39a141ab3c82b51951bb51a2e3787ac7"}

        {"facts" {"href" (str "/" (name version) "/factsets/foo2/facts")
                  "data" [{"name" "uptime_seconds"
                           "value" "6000"}
                          {"name" "domain"
                           "value" "testing.com"}
                          {"name" "my_structured_fact"
                           "value" {"d" {"n" ""}, "b" 3.14, "c" ["a" "b" "c"]
                                    "a" 1, "e" "1"}}]},
         "timestamp" "2013-01-01T00:00:00.000Z"
         "environment" "DEV"
         "certname" "foo2"
         "producer_timestamp" "2013-01-01T00:00:00.000Z"
         "hash" "28ea981ebb992fa97a1ba509790fd213d0f98411"}

        {"facts" {"href" (str "/" (name version) "/factsets/foo3/facts")
                  "data" [{"name" "domain"
                           "value" "testing.com"}
                          {"name" "operatingsystem"
                           "value" "Darwin"}
                          {"name" "my_structured_fact"
                           "value" {"e" "1", "b" 3.14, "f" nil, "a" 1,
                                    "d" {"n" ""}, "" "g?", "c" ["a" "b" "c"]}}]},
         "environment" "PROD"
         "certname" "foo3"
         "hash" "f1122885dd4393bd1b786751384728bd1ca97bab"}]))

(deftestseq factset-paging-results
  [[version endpoint] factsets-endpoints
   method [:get :post]]
  (let [factset-count 3]
    (populate-for-structured-tests reference-time)
    (testing "include total results count"
      (let [actual (json/parse-string
                    (slurp (:body (query-response method endpoint nil
                                                  {:include_total true}))))]
        (is (= (count actual) factset-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 factset-count]]]
        (let [results (query-endpoint endpoint {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order_by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order_by.*"
                        (:body (query-response
                                 method endpoint nil
                                 {:order_by (vector-param method
                                                         [{"field" "invalid-field"
                                                           "order" "ASC"}])})))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" (sort-by #(get % "certname") (factset-results version))]
                                  ["DESC" (reverse (sort-by #(get % "certname") (factset-results version)))]]]
          (testing order
            (let [ordering {:order_by (vector-param method [{"field" "certname" "order" order}])}
                  actual (json/parse-string (slurp (:body (query-response
                                                            method endpoint nil ordering))))]
              (is (= (munge-factsets-response actual) expected))))))

      (testing "order on hash"
        (doseq [[order expected] [["ASC" (sort-by #(get % "hash") (factset-results version))]
                                  ["DESC" (reverse (sort-by #(get % "hash") (factset-results version)))]]]
          (testing order
            (let [ordering {:order_by (vector-param method [{"field" "hash" "order" order}])}
                  actual (json/parse-string (slurp (:body (query-response
                                                            method endpoint nil ordering))))]
              (is (= (munge-factsets-response actual) expected))))))

      (testing "order on unextracted field with function alias"
        (doseq
          [[order expected] [["ASC" (sort-by #(get % "hash")
                                             (factset-results version))]
                             ["DESC" (reverse (sort-by #(get % "hash")
                                                       (factset-results version)))]]]
          (testing order
            (let [ordering {:order_by
                            (vector-param method [{"field" "hash" "order" order}])}
                  query ["extract" "certname" ["~" "certname" ".*"]]
                  actual (json/parse-string
                           (slurp (:body (query-response
                                          method endpoint query ordering))))]
              (is (= (map :certname (munge-factsets-response actual))
                     (map :certname expected)))))))

      (testing "multiple fields"
        (doseq [[[env-order certname-order] expected-order] [[["DESC" "ASC"]  [2 0 1]]
                                                             [["DESC" "DESC"] [2 1 0]]
                                                             [["ASC" "DESC"]  [1 0 2]]
                                                             [["ASC" "ASC"]   [0 1 2]]]]
          (testing (format "environment %s certname %s" env-order certname-order)
            (let [params {:order_by
                          (vector-param method [{"field" "environment" "order" env-order}
                                                 {"field" "certname" "order" certname-order}])}
                  actual (json/parse-string (slurp (:body (query-response
                                                           method endpoint nil params))))]
              (is (= (munge-factsets-response actual) (map #(nth (factset-results version) %) expected-order))))))
        (doseq [[[pt-order certname-order] expected-order] [[["DESC" "ASC"]  [0 2 1]]
                                                            [["DESC" "DESC"] [2 0 1]]
                                                            [["ASC" "DESC"]  [1 2 0]]
                                                            [["ASC" "ASC"]   [1 0 2]]]]
          (testing (format "producer_timestamp %s certname %s" pt-order certname-order)
            (let [params {:order_by
                          (vector-param method [{"field" "producer_timestamp" "order" pt-order}
                                                 {"field" "certname" "order" certname-order}])}
                  actual (json/parse-string (slurp (:body (query-response
                                                           method endpoint nil params))))]
              (is (= (munge-factsets-response actual) (map #(nth (factset-results version) %) expected-order))))))))

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
          (let [params {:order_by (vector-param method [{"field" "certname" "order" order}]) :offset offset}
                actual (json/parse-string (slurp (:body (query-response
                                                         method endpoint nil params))))]
            (is (= (munge-factsets-response actual) (map #(nth (factset-results version) %) expected-order)))))))))

(deftestseq factset-queries
  [[version endpoint] factsets-endpoints
   method [:get :post]]
  (populate-for-structured-tests reference-time)

  (testing "query without param should not fail"
    (let [response (query-response method endpoint)]
      (assert-success! response)
      (slurp (:body response))))

  (testing "factsets query should ignore deactivated nodes"
    (let [responses (json/parse-string (slurp (:body (query-response
                                                       method endpoint))))]
      (is (not (contains? (into [] (map #(get % "certname") responses)) "foo4")))))

  (testing "factset queries should return appropriate results"
    (let [queries [["=" "certname" "foo1"]
                   ["=" "environment" "DEV"]
                   ["<" "timestamp" "2014-01-01"]
                   ["<" "producer_timestamp" "2014-01-01"]
                   ["extract" ["certname" "hash"]
                    ["=" "certname" "foo1"]]]
          responses (map (comp json/parse-string
                               slurp
                               :body
                               (partial query-response method endpoint))
                         queries)]
      (is (= (munge-factset-response (into {} (first responses)))
             (strip-expanded
              {"facts" {"href" (str "/" (name version) "/factsets/foo1/facts")
                        "data" [{"name" "domain"
                                 "value" "testing.com"}
                                {"name" "my_structured_fact"
                                 "value"
                                 {"a" 1
                                  "b" 3.14
                                  "c" ["a" "b" "c"]
                                  "d" {"n" ""}
                                  "e" "1"
                                  "f" nil}}
                                {"name" "test#~delimiter"
                                 "value" "foo"}
                                {"name" "uptime_seconds"
                                 "value" "4000"}]}
               "timestamp" reference-time
               "producer_timestamp" reference-time
               "environment" "DEV"
               "certname" "foo1"
               "hash" "b966980c39a141ab3c82b51951bb51a2e3787ac7"})))
      (is (= (munge-factsets-response (into [] (second responses)))
             (map strip-expanded
                  [{"facts" {"href" (str "/" (name version) "/factsets/foo1/facts")
                             "data" [{"name" "my_structured_fact"
                                      "value"
                                      {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"
                                       "f" nil}}
                                     {"name" "domain"
                                      "value" "testing.com"}
                                     {"name" "uptime_seconds"
                                      "value" "4000"}
                                     {"name" "test#~delimiter"
                                      "value" "foo"}]}
                    "timestamp" reference-time
                    "producer_timestamp" reference-time
                    "environment" "DEV"
                    "certname" "foo1"
                    "hash" "b966980c39a141ab3c82b51951bb51a2e3787ac7"}

                   {"facts" {"href" (str "/" (name version) "/factsets/foo2/facts")
                             "data" [{"name" "my_structured_fact"
                                      "value"
                                      {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"}}
                                     {"name" "domain"
                                      "value" "testing.com"}
                                     {"name" "uptime_seconds"
                                      "value" "6000"}]}
                    "timestamp" (to-string (to-timestamp "2013-01-01"))
                    "producer_timestamp" (to-string (to-timestamp "2013-01-01"))
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "28ea981ebb992fa97a1ba509790fd213d0f98411"}])))

      (is (= (munge-factsets-response (into [] (nth responses 2)))
             (map strip-expanded
                  [{"facts" {"href" (str "/" (name version) "/factsets/foo2/facts")
                             "data" [{"name" "my_structured_fact"
                                      "value"
                                      {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"}}
                                     {"name" "domain"
                                      "value" "testing.com"}
                                     {"name" "uptime_seconds"
                                      "value" "6000"}]}
                    "timestamp" (to-string (to-timestamp "2013-01-01"))
                    "producer_timestamp" (to-string (to-timestamp "2013-01-01"))
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "28ea981ebb992fa97a1ba509790fd213d0f98411"}])))
      (is (= (munge-factsets-response (into [] (nth responses 3)))
             (map strip-expanded
                  [{"facts" {"href" (str "/" (name version) "/factsets/foo2/facts")
                             "data" [{"name" "my_structured_fact"
                                      "value"
                                      {"a" 1
                                       "b" 3.14
                                       "c" ["a" "b" "c"]
                                       "d" {"n" ""}
                                       "e" "1"}}
                                     {"name" "domain"
                                      "value" "testing.com"}
                                     {"name" "uptime_seconds"
                                      "value" "6000"}]}
                    "timestamp" (to-string (to-timestamp "2013-01-01"))
                    "producer_timestamp" (to-string (to-timestamp "2013-01-01"))
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "28ea981ebb992fa97a1ba509790fd213d0f98411"}])))
      (is (= (into [] (nth responses 4))
             [{"certname" "foo1"
               "hash" "b966980c39a141ab3c82b51951bb51a2e3787ac7"}])))))

(deftestseq factset-single-response
  [[version endpoint] factsets-endpoints
   method [:get :post]]
  (populate-for-structured-tests reference-time)

  (testing "querying singleton endpoint should return a single result"
    (let [response (json/parse-string (:body (query-response method (str endpoint "/foo1"))))]
      (is (= (munge-factset-response response)
             (strip-expanded {"certname" "foo1"
                              "environment" "DEV"
                              "facts" {"data" #{{"name" "my_structured_fact", "value" {"a" 1, "b" 3.14, "c" ["a" "b" "c"], "d" {"n" ""}, "e" "1", "f" nil}}
                                                {"name" "domain", "value" "testing.com"}
                                                {"name" "test#~delimiter", "value" "foo"}
                                                {"name" "uptime_seconds", "value" "4000"}}
                                       "href" "/v4/factsets/foo1/facts"}
                              "hash" "b966980c39a141ab3c82b51951bb51a2e3787ac7"
                              "producer_timestamp" "2014-10-28T20:26:21.727Z"
                              "timestamp" "2014-10-28T20:26:21.727Z"}))))))


;; STRUCTURED FACTS TESTS

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
      {:value  {:d  {:n ""} :b 3.14 :a 1 :e "1" :c  ["a" "b" "c"]} :name "my_structured_fact" :environment "DEV" :certname "foo2"}]

     ["extract" [["function" "max" "value"]] ["=" "name" "uptime_seconds"]]
     [{:max 6000.0}]

     ["extract" [["function" "avg" "value"]] ["=" "name" "uptime_seconds"]]
     [{:avg 5000.0}]

     ["extract" [["function" "count"] "value"] ["=" "name" "uptime_seconds"]
      ["group_by" "value"]]
     [{:value 4000 :count 1}
      {:value 6000 :count 1}]

     ["extract" [["function" "max" "name"] "environment"] ["~" "certname" ".*"]
      ["group_by" "environment"]]
     [{:environment "DEV" :max "uptime_seconds"}]}

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
      {:value {:b 3.14 :a 1 :d {:n ""} :c ["a" "b" "c"] :e "1"} :name "my_structured_fact" :environment "PROD" :certname "foo3"}]

     ["extract" [["function" "max" "value"]] ["=" "name" "uptime_seconds"]]
     [{:max 6000.0}]

     ["extract" [["function" "avg" "value"]] ["=" "name" "uptime_seconds"]]
     [{:avg 5000.0}]

     ["extract" [["function" "count"] "value"] ["=" "name" "uptime_seconds"]
      ["group_by" "value"]]
     [{:value 4000
       :count 1}
      {:value 6000
       :count 1}]

     ["extract" [["function" "max" "name"] "environment"] ["~" "certname" ".*"]
      ["group_by" "environment"]]
     [{:environment "DEV" :max "uptime_seconds"}
      {:environment "PROD" :max "operatingsystem"}]}

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
      {:value "{\"b\":3.14,\"a\":1,\"d\":{\"n\":\"\"},\"c\":[\"a\",\"b\",\"c\"],\"e\":\"1\"}" :name "my_structured_fact" :certname "foo3"}]

     ["extract" [["function" "max" "value"]] ["=" "name" "uptime_seconds"]]
     [{:max 6000.0}]

     ["extract" [["function" "avg" "value"]] ["=" "name" "uptime_seconds"]]
     [{:avg 5000.0}]

     ["extract" [["function" "count"] "value"] ["=" "name" "uptime_seconds"]
      ["group_by" "value"]]
     []

     ["extract" [["function" "max" "name"] "environment"] ["~" "certname" ".*"]
      ["group_by" "environment"]]
     [{:environment "DEV" :max "uptime_seconds"}]}))

(deftestseq structured-fact-queries
  [[version endpoint] facts-endpoints
   method [:get :post]]
  (let [facts1 {"my_structured_fact" {"a" 1
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
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp reference-time
                             :environment "DEV"
                             :producer_timestamp reference-time})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer_timestamp reference-time})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time})
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (query-response method endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact queries should return appropriate results"
      (let [queries [["=" "certname" "foo1"]
                     ["=" "value" 3.14]
                     ["<=" "value" 10]
                     [">=" "value" 10]
                     ["<" "value" 10]
                     [">" "value" 10]
                     ["=" "name" "my_structured_fact"]
                     ["extract" [["function" "max" "value"]] ["=" "name" "uptime_seconds"]]
                     ["extract" [["function" "avg" "value"]] ["=" "name" "uptime_seconds"]]
                     ["extract" [["function" "count"] "value"] ["=" "name" "uptime_seconds"]
                      ["group_by" "value"]]
                     ["extract" [["function" "max" "name"] "environment"] ["~" "certname" ".*"]
                      ["group_by" "environment"]]]
            responses (map (comp parse-result
                                 :body
                                 (partial query-response method endpoint)) queries)]

        (doseq [[response query] (map vector responses queries)]
          (is (= (set response)
                 (set (get (structured-fact-results version endpoint)
                           query)))))))))

;; FACT-CONTENTS TESTS

(defn fact-content-response [method endpoint order-by-map]
  (fn [req]
    (-> (query-response method endpoint req order-by-map)
        :body
        slurp
        json/parse-string)))

(deftestseq fact-contents-queries
  [[version endpoint] fact-contents-endpoints
   method [:get :post]]
  (populate-for-structured-tests reference-time)

  (testing "query without param should not fail"
    (let [response (query-response method endpoint)]
      (assert-success! response)
      (slurp (:body response))))

  (testing "fact nodes queries should ignore deactivated nodes"
    (let [responses (json/parse-string (slurp (:body (query-response method endpoint))))]
      (is (not (contains? (into [] (map #(get % "certname") responses)) "foo4")))))

  (testing "fact nodes queries should return appropriate results"
    (let [response (fact-content-response method endpoint {:order_by (vector-param method [{:field "path"} {:field "certname"}])})]
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
              {"certname" "foo3", "path" ["domain"], "name" "domain", "value" "testing.com", "environment" "PROD"}]))))

  (testing "fact nodes queries should return appropriate results"
    (let [response (fact-content-response method endpoint {})]
      (is (= (response ["extract" "value" ["=", "name" "domain"]])
             [{"value" "testing.com"}
              {"value" "testing.com"}
              {"value" "testing.com"}]))
      (is (= (sort-by #(get % "certname")
                      (response ["extract" ["certname" "value"] ["=", "name" "domain"]]))
             (sort-by #(get % "certname")
                      [{"certname" "foo1" "value" "testing.com"}
                       {"certname" "foo2" "value" "testing.com"}
                       {"certname" "foo3" "value" "testing.com"}]))))))

(def no-parent-endpoints [[:v4 "/v4/factsets/foo/facts"]])

(deftestseq unknown-parent-handling
  [[version endpoint] no-parent-endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= status http/status-not-found))
    (is (= {:error "No information is known about factset foo"} (json/parse-string body true)))))
