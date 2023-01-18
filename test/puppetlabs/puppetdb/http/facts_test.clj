(ns puppetlabs.puppetdb.http.facts-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.testutils
             :refer [get-request
                     assert-success!
                     paged-results
                     paged-results*
                     parse-result]]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db*
                     *read-db*
                     call-with-test-dbs
                     clear-db-for-testing!
                     init-db
                     with-test-db]]
            [puppetlabs.puppetdb.testutils.http
             :refer [*app*
                     are-error-response-headers
                     deftest-http-app
                     is-query-result
                     query-response
                     query-result
                     vector-param
                     with-http-app]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [call-with-puppetdb-instance]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.time :refer [now to-string to-timestamp parse-period] :as t])
  (:import
   (java.net HttpURLConnection)))

(def v4-facts-endpoint "/v4/facts")
(def v4-facts-environment "/v4/environments/DEV/facts")
(def facts-endpoints [[:v4 v4-facts-endpoint]
                      [:v4 v4-facts-environment]])

(def factsets-endpoints [[:v4 "/v4/factsets"]])

(def fact-contents-endpoints [[:v4 "/v4/fact-contents"]])

(def reference-time "2014-10-28T20:26:21.727Z")

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

   ;; array query with value field
   ["in" "value" ["array" ["192.168.1.100"]]]
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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100" :environment "DEV"}}

   ;;;;;;;;;
   ;; Fact-contents subqueries
   ;;;;;;;;;

   ;; In syntax
   ["in" ["certname" "name"]
    ["extract" ["certname" "name"]
     ["select_fact_contents"
      ["and"
       ["=" "path" ["osfamily"]]
       ["=" "value" "Debian"]]]]]
   #{{:certname "bar" :environment "DEV" :name "osfamily" :value "Debian"}
     {:certname "foo" :environment "DEV" :name "osfamily" :value "Debian"}}

   ["extract" [["function" "avg" "value"]]
    ["and"
     ["=" "name" "uptime_seconds"]
     ["=" "certname" "foo"]]]
   #{{:avg 11000.0}}

   ;; Implicit subquery
   ["subquery" "fact_contents"
    ["and"
     ["=" "path" ["osfamily"]]
     ["=" "value" "Debian"]]]
   #{{:certname "bar" :environment "DEV" :name "osfamily" :value "Debian"}
     {:certname "foo" :environment "DEV" :name "osfamily" :value "Debian"}}))

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
                "Can't extract unknown 'resources' field 'nothing'. Acceptable fields are 'certname', 'environment', 'exported', 'file', 'line', 'parameters', 'resource', 'tag', 'tags', 'title', and 'type'"

                ["in" "certname" ["extract" ["nothing" "nothing2" "certname"] ["select_resources"
                                                                               ["=" "type" "Class"]]]]
                "Can't extract unknown 'resources' fields 'nothing' and 'nothing2'. Acceptable fields are 'certname', 'environment', 'exported', 'file', 'line', 'parameters', 'resource', 'tag', 'tags', 'title', and 'type'"

                ;; In-query for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select_resources"
                                                        ["=" "type" "Class"]]]]
                "Can't match on unknown 'facts' field 'nothing' for 'in'. Acceptable fields are 'certname', 'environment', 'name', and 'value'"

                ["in" ["name" "nothing" "nothing2"] ["extract" "certname" ["select_resources"
                                                                            ["=" "type" "Class"]]]]
                "Can't match on unknown 'facts' fields 'nothing' and 'nothing2' for 'in'. Acceptable fields are 'certname', 'environment', 'name', and 'value'")))

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
                #"Can't extract unknown 'facts' fields 'nothing' and 'nothing2'.*Acceptable fields are.*"

                ["~>" "test" "test" "test"] #"~> requires exactly two arguments"
                ["~>" "test"] #"~> requires exactly two arguments"
                [">" "test" 50 100] #"> requires exactly two arguments"
                [">" "test"] #"> requires exactly two arguments"
                [">=" "test" 50 100] #">= requires exactly two arguments"
                [">=" "test"] #">= requires exactly two arguments"
                ["<" "test" 50 100] #"< requires exactly two arguments"
                ["<" "test"] #"< requires exactly two arguments"
                ["<=" "test" 50 100] #"<= requires exactly two arguments"
                ["<=" "test"] #"<= requires exactly two arguments")))

(deftest-http-app invalid-projections
  [[_version endpoint] facts-endpoints
   method [:get :post]]
  (doseq [[query msg] (get versioned-invalid-queries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)))))

(deftest valid-projections
  (testing "Projection works with ~> (regexp array match) operator"
    (with-test-db
      (with-http-app
        (let [query ["from" "fact_contents"
                     ["extract" ["certname" "value"]
                      ["~>" "path" ["virtual"]]] ["order_by" ["certname"]]]
              {:keys [status]} (query-response :post "/v4" query)]
          (is (= java.net.HttpURLConnection/HTTP_OK status)))))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/facts" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftest-http-app pg-invalid-regexps
  [[_version endpoint] facts-endpoints
   method [:get :post]]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body headers]} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
        (are-error-response-headers headers)))))

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

   ;; Can ask for nodes regardless of active/inactive status
   ;; In an HA setup, these are the nodes that will be kept in sync
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
    {:certname "foo4", :environment "DEV", :name "hostname", :value "foo4"}]

   ;; ...and for all nodes in the database regardless of status
   ["=" "node_state" "any"]
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
    {:certname "foo4", :environment "DEV", :name "hostname", :value "foo4"}
    {:certname "foo5", :environment "DEV", :name "operatingsystem", :value "RedHat"}
    {:certname "foo5", :environment "DEV", :name "kernel", :value "Linux"}
    {:certname "foo5", :environment "DEV", :name "domain", :value "testing.com"}
    {:certname "foo5", :environment "DEV", :name "uptime_seconds", :value 6000}
    {:certname "foo5", :environment "DEV", :name "hostname", :value "foo5"}]))


(defn well-formed-tests
  []
  ;; The reason for the "common" split is historical.  These
  ;; non-common tests used to vary based on the endpoint version.
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

          ["extract" "certname"]
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
                                 :scf-write-dbs [write-db]
                                 :scf-write-db-names ["default"]
                                 :product-name "puppetdb"
                                 :add-agent-report-filter true
                                 :node-purge-ttl (parse-period "14d")
                                 :url-prefix "/pdb")))))

(deftest-http-app fact-queries
  [[_version endpoint] facts-endpoints
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
                "uptime_seconds" 6000}
        facts5 {"domain" "testing.com"
                "hostname" "foo5"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" 6000}]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-certname! "foo5")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer "bar1"})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer "bar2"})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer nil})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer "bar4"})
      (scf-store/add-facts! {:certname "foo5"
                             :values facts5
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (-> 16 t/days t/ago)
                             :producer "bar5"})
      (scf-store/deactivate-node! "foo4")
      ; This is simulating a node that was deactivated before node-purge-ttl but
      ; has not been garbage collected. It should be neither active nor inactive
      (scf-store/deactivate-node! "foo5" (-> 15 t/days t/ago)))

    (testing "query without param should not fail"
      (let [response (query-response method endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact queries"
      (testing "well-formed queries"
        (doseq [[query result] (well-formed-tests)]
          (testing (format "Query %s" query)
            (let [request (if query
                            (get-request endpoint (json/generate-string query))
                            (get-request endpoint))
                  {:keys [status body headers]} (*app* request)]
              (is (= HttpURLConnection/HTTP_OK status))
              (is (http/json-utf8-ctype? (headers "Content-Type")))
              (is (= (set result)
                     (set (json/parse-string (slurp body) true))))))))

      (testing "malformed, yo"
        (let [request (get-request endpoint (json/generate-string []))
              {:keys [status body headers]} (*app* request)]
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers)
          (is (= body "[] is not well-formed: queries must contain at least one operator"))))

      (testing "'not' with too many arguments"
        (let [request (get-request endpoint (json/generate-string ["not" ["=" "name" "ipaddress"] ["=" "name" "operatingsystem"]]))
              {:keys [status body headers]} (*app* request)]
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers)
          (is (= body "'not' takes exactly one argument, but 2 were supplied")))))))

(deftest-http-app fact-subqueries
  [[_version endpoint] facts-endpoints
   method [:get :post]]

  (scf-store/add-certname! "foo")
  (scf-store/add-certname! "bar")
  (scf-store/add-certname! "baz")
  (scf-store/add-facts! {:certname "foo"
                         :values {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)
                         :producer "mom"})
  (scf-store/add-facts! {:certname "bar"
                         :values {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)
                         :producer "mom"})
  (scf-store/add-facts! {:certname "baz"
                         :values {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000}
                         :timestamp (now)
                         :environment "DEV"
                         :producer_timestamp (now)
                         :producer "mom"})

  (let [catalog (:empty catalogs)
        apache-resource {:type "Class" :title "Apache"}
        apache-catalog (update-in catalog [:resources] conj {apache-resource (assoc apache-resource :exported false)})]
    (scf-store/replace-catalog! (assoc apache-catalog :certname "foo") (now))
    (scf-store/replace-catalog! (assoc apache-catalog :certname "bar") (now))
    (scf-store/replace-catalog! (assoc catalog :certname "baz") (now)))

  (doseq [[query results] (get versioned-subqueries endpoint)]
    (testing (str "query: " query " should match expected output")
      (is-query-result method endpoint query (set results))))

  (testing "subqueries: invalid"
    (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
      (testing (str "query: " query " should fail with msg: " msg)
        (let [request (get-request endpoint (json/generate-string query))
              {:keys [status body headers]} (*app* request)]
          (is (= body msg))
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers))))))

(deftest-http-app two-database-fact-query-config
  [[_version endpoint] facts-endpoints
   method [:get :post]]

  (call-with-test-dbs
   2
   (fn [db-for-reads db-for-writes]
     (let [config (-> (svc-utils/create-temp-config)
                      (assoc :read-database (:read-config db-for-reads))
                      (assoc :database (:write-config db-for-writes)))]
       (clear-db-for-testing! (:write-config db-for-reads))
       (clear-db-for-testing! (:write-config db-for-writes))
       (init-db (:write-config db-for-reads))
       (init-db (:write-config db-for-writes))
       (call-with-puppetdb-instance
        config
        (fn []
          (let [pdb (get-service svc-utils/*server* :PuppetDBServer)
                shared-globals (cli-svc/shared-globals pdb)
                read-db (:scf-read-db shared-globals)
                [write-db & more-dbs] (:scf-write-dbs shared-globals)
                _ (assert (not (seq more-dbs)))
                one-db-app (test-app write-db)
                two-db-app (test-app read-db write-db)
                facts1 {"domain" "testing.com"
                        "hostname" "foo1"
                        "kernel" "Linux"
                        "operatingsystem" "Debian"
                        "some_version" "1.3.7+build.11.e0f985a"
                        "uptime_seconds" "4000"}]

            (jdbc/with-transacted-connection write-db
              (scf-store/add-certname! "foo1")
              (scf-store/add-facts! {:certname "foo1"
                                     :values facts1
                                     :timestamp (now)
                                     :environment "DEV"
                                     :producer_timestamp (now)
                                     :producer "bar1"}))

            (testing "queries only use the read database"
              (let [{:keys [status body headers]}
                    (binding [*app* two-db-app]
                      (query-response method endpoint))]
                (is (http/json-utf8-ctype? (headers "Content-Type")))
                ;; Environments endpoint will return a proper JSON
                ;; error with a 404, as opposed to an empty array.
                (if (= endpoint "/v4/environments/DEV/facts")
                  (do
                    (is (= {:error "No information is known about environment DEV"}
                           (json/parse-string body true)))
                    (is (= HttpURLConnection/HTTP_NOT_FOUND status)))
                  (do
                    (is (empty? (json/parse-stream (io/reader body) true)))
                    (is (= HttpURLConnection/HTTP_OK status))))))

            (testing "config with only a single database returns results"
              (let [{:keys [status body headers]}
                    (binding [*app* one-db-app]
                      (query-response method endpoint))]
                (is (= HttpURLConnection/HTTP_OK status))
                (is (http/json-utf8-ctype? (headers "Content-Type")))
                (is (= [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
                        {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
                        {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
                        {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
                        {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
                        {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}]
                       (sort-by :name (json/parse-stream (io/reader body)
                                                         true)))))))))))))

(defn test-paged-results
  [method endpoint query limit total include_total]
  (paged-results method
                 {:app-fn  *app*
                  :path    endpoint
                  :query   query
                  :limit   limit
                  :total   total
                  :params {:order_by (vector-param method
                                                   [{:field :certname
                                                     :order :desc}
                                                    {:field :name
                                                     :order :desc}])}
                  :include_total include_total}))

(deftest-http-app fact-query-paging
  [[_version endpoint] facts-endpoints
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
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer "bar1"})
      (scf-store/add-facts! {:certname "foo2"
                             :values facts2
                             :timestamp (now)
                             :environment "DEV"
                             :producer_timestamp (now)
                             :producer "bar2"}))

    (testing "should support fact paging"
      (doseq [[label counts?] [["without" false]
                               ["with" true]]]
        (testing (str "should support paging through facts " label " counts")
          (let [results (test-paged-results method endpoint
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

(deftest-http-app paging-results
  [[_version endpoint] facts-endpoints
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
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:certname "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:certname "d.local"
                           :values {"uptime_days" "2"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:certname "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "e.local")
    (scf-store/add-facts! {:certname "e.local"
                           :values {"my_structured_fact" (:value f5)}
                           :timestamp (now)
                           :environment "DEV"
                           :producer_timestamp (now)
                           :producer "foo.com"})

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
              (is (= (map unkeywordize-values actual) expected))))))

      (testing "works on value"
        (doseq [[order expected] [["ASC" [f1 f3]]
                                  ["DESC" [f3 f1]]]]
          (testing order
            (let [actual (->> {:order_by (vector-param method [{"field" "value" "order" order}])}
                              (query-response method endpoint ["=" "name" "hostname"])
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (is (= (map unkeywordize-values actual) expected))))))

      (testing "unextracted field with alias"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4 f5]]
                                  ["DESC" [f5 f4 f3 f2 f1]]]]
          (testing order
            (let [actual (->> {:order_by (vector-param method [{"field" "certname" "order" order}])}
                              (query-response method endpoint ["extract" "environment" ["~" "certname" ".*"]])
                              :body
                              slurp)
                  actual (json/parse-string actual true)]
              (is (= (map (comp :environment unkeywordize-values) actual)
                     (map :environment expected)))))))

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
              (is (= (map unkeywordize-values actual) expected)))))))

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
              (is (= (map unkeywordize-values actual) expected)))))))))

(deftest-http-app facts-environment-paging
  [[_version endpoint] facts-endpoints
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
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! {:certname "a.local"
                           :values {"hostname" "a-host"}
                           :timestamp (now)
                           :environment "A"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! {:certname "b.local"
                           :values {"uptime_days" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "b2.local")
    (scf-store/add-facts! {:certname "b2.local"
                           :values {"max" "4"}
                           :timestamp (now)
                           :environment "B"
                           :producer_timestamp (now)
                           :producer "foo.com"})
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! {:certname "d.local"
                           :values {"min" "-4"}
                           :timestamp (now)
                           :environment "D"
                           :producer_timestamp (now)
                           :producer "foo.com"})

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
            (is (= (map unkeywordize-values (json/parse-string (slurp (:body actual)) true))
                   expected))))))))

(deftest-http-app fact-environment-queries
  [[_version endpoint] facts-endpoints
   method [:get :post]
   :when (not (re-find #"environment" endpoint))]

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
      (jdbc/with-transacted-connection *db*
        (scf-store/add-certname! "foo1")
        (scf-store/add-certname! "foo2")
        (scf-store/add-certname! "foo3")
        (scf-store/add-certname! "foo4")
        (scf-store/add-facts! {:certname "foo1"
                               :values facts1
                               :timestamp (now)
                               :environment "DEV"
                               :producer_timestamp (now)
                               :producer "foo.com"})
        (scf-store/add-facts! {:certname "foo2"
                               :values facts2
                               :timestamp (now)
                               :environment "DEV"
                               :producer_timestamp (now)
                               :producer "foo.com"})
        (scf-store/add-facts! {:certname "foo3"
                               :values facts3
                               :timestamp (now)
                               :environment "PROD"
                               :producer_timestamp (now)
                               :producer "foo.com"})
        (scf-store/add-facts! {:certname "foo4"
                               :values facts4
                               :timestamp (now)
                               :environment "PROD"
                               :producer_timestamp (now)
                               :producer "foo.com"}))

      (doseq [query '[[= environment PROD]
                      [not [= environment DEV]]
                      ["~" environment PR.*]
                      [not ["~" environment DE.*]]]]
        (let [{:keys [status headers body]} (query-response method endpoint query)
              results (json/parse-string (slurp body) true)]
          (is (= HttpURLConnection/HTTP_OK status))
          (is (http/json-utf8-ctype? (headers "Content-Type")))
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
                "uptime_seconds" "6000"}
        package-inventory [["package1" "1.2.3" "provider1"]
                           ["package2" "3.2.1" "provider1"]
                           ["package3" "5" "provider2"]]]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp test-time
                             :environment "DEV"
                             :package_inventory package-inventory
                             :producer_timestamp test-time
                             :producer "bar1"})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :package_inventory package-inventory
                             :producer_timestamp (to-timestamp "2013-01-01")
                             :producer "bar2"})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp test-time
                             :environment "PROD"
                             :package_inventory package-inventory
                             :producer_timestamp test-time
                             :producer "bar3"})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp test-time
                             :environment "PROD"
                             :package_inventory package-inventory
                             :producer_timestamp test-time
                             :producer "bar4"})
      (scf-store/deactivate-node! "foo4"))))

;; FACTSETS TRANSFORMATION

(defn munge-factset-response
  [factset]
  (update-in factset ["facts" "data"] set))

(defn munge-factsets-response
  [factsets]
  (map munge-factset-response
       factsets))

;; FACTSETS TESTS

(defn factset-results
  [version]
  (map (comp munge-factset-response
             #(utils/assoc-when % "timestamp" reference-time "producer_timestamp" reference-time))
       [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo1/facts")
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
         "producer" "bar1"
         "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"}

        {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
         "producer" "bar2"
         "hash" "39ad058afe565c797e925a862394d1bf457cf592"}

        {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo3/facts")
                  "data" [{"name" "domain"
                           "value" "testing.com"}
                          {"name" "operatingsystem"
                           "value" "Darwin"}
                          {"name" "my_structured_fact"
                           "value" {"e" "1", "b" 3.14, "f" nil, "a" 1,
                                    "d" {"n" ""}, "" "g?", "c" ["a" "b" "c"]}}]},
         "environment" "PROD"
         "certname" "foo3"
         "producer" "bar3"
         "hash" "f78da40ed4ad5f8009bf1e9e2963e44a86cfef00"}]))

(deftest-http-app factset-paging-results
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

(deftest-http-app factset-queries
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
                   ["=" "certname" "foo1"]]
                   ["=" "producer" "bar2"]]
          responses (map (comp json/parse-string
                               slurp
                               :body
                               (partial query-response method endpoint))
                         queries)]
      (is (= (munge-factset-response (into {} (first responses)))
             (munge-factset-response
              {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo1/facts")
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
               "producer" "bar1"
               "environment" "DEV"
               "certname" "foo1"
               "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"})))
      (is (= (munge-factsets-response (into [] (second responses)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo1/facts")
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
                    "producer" "bar1"
                    "environment" "DEV"
                    "certname" "foo1"
                    "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"}

                   {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))

      (is (= (munge-factsets-response (into [] (nth responses 2)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))
      (is (= (munge-factsets-response (into [] (nth responses 3)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))
      (is (= (into [] (nth responses 4))
             [{"certname" "foo1"
               "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"}]))
     (is (= (munge-factsets-response (into [] (nth responses 5)))
            (map munge-factset-response
                 [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                   "producer" "bar2"
                   "environment" "DEV"
                   "certname" "foo2"
                   "hash" "39ad058afe565c797e925a862394d1bf457cf592"}]))))))

(deftest-http-app factset-subqueries
  [[_version endpoint] factsets-endpoints
   method [:get :post]]

  (populate-for-structured-tests reference-time)

  (are [query expected]
      (is (= expected
             (query-result method endpoint query)))

    ;;;;;;;;;;;;;;
    ;; Facts subqueries
    ;;;;;;;;;;;;;;

    ;; In: select_facts
    ["extract" "certname"
     ["in" "certname"
      ["extract" "certname"
       ["select_facts"
        ["and"
         ["=" "name" "uptime_seconds"]
         ["=" "value" "4000"]]]]]]
    #{{:certname "foo1"}}

    ;; In: from facts
    ["extract" "certname"
     ["in" "certname"
      ["from" "facts"
       ["extract" "certname"
        ["and"
         ["=" "name" "uptime_seconds"]
         ["=" "value" "4000"]]]]]]
    #{{:certname "foo1"}}

    ;; Implicit subquery
    ["extract" "certname"
     ["subquery" "facts"
      ["and"
       ["=" "name" "uptime_seconds"]
       ["=" "value" "4000"]]]]
    #{{:certname "foo1"}}

    ;;;;;;;;;;;;;
    ;; Fact content subqueries
    ;;;;;;;;;;;;;

    ;; In: select_fact_contents
    ["extract" "certname"
     ["in" "certname"
      ["extract" "certname"
       ["select_fact_contents"
        ["and"
         ["=" "name" "uptime_seconds"]
         ["=" "value" "4000"]]]]]]
    #{{:certname "foo1"}}

    ;; In: from fact_contents
    ["extract" "certname"
     ["in" "certname"
      ["from" "fact_contents"
       ["extract" "certname"
        ["and"
         ["=" "name" "uptime_seconds"]
         ["=" "value" "4000"]]]]]]
    #{{:certname "foo1"}}

    ;; Implicit subqueries
    ["extract" "certname"
     ["subquery" "fact_contents"
      ["and"
       ["=" "name" "uptime_seconds"]
       ["=" "value" "4000"]]]]
    #{{:certname "foo1"}}

    ;; Nested implicit subqueries
    ["extract" "certname"
     ["subquery" "fact_contents"
      ["and"
       ["subquery" "nodes" ["~" "certname" ".*"]]
       ["=" "name" "uptime_seconds"]
       ["=" "value" "4000"]]]]
    #{{:certname "foo1"}}))

(deftest-http-app factset-single-response
  [[_version endpoint] factsets-endpoints
   method [:get :post]]
  (populate-for-structured-tests reference-time)

  (testing "querying singleton endpoint should return a single result"
    (let [response (json/parse-string (:body (query-response method (str endpoint "/foo1"))))]
      (is (= (munge-factset-response response)
             (munge-factset-response
              {"certname" "foo1"
               "environment" "DEV"
               "facts" {"data" #{{"name" "my_structured_fact",
                                  "value" {"a" 1
                                           "b" 3.14
                                           "c" ["a" "b" "c"]
                                           "d" {"n" ""}
                                           "e" "1"
                                           "f" nil}}
                                 {"name" "domain", "value" "testing.com"}
                                 {"name" "test#~delimiter" "value" "foo"}
                                 {"name" "uptime_seconds" "value" "4000"}}
                        "href" "/pdb/query/v4/factsets/foo1/facts"}
               "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"
               "producer_timestamp" "2014-10-28T20:26:21.727Z"
               "producer" "bar1"
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
     [{:max 6000}]

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
     [{:max 6000}]

     ["extract" [["function" "avg" "value"]] ["=" "name" "uptime_seconds"]]
     [{:avg 5000.0}]

     ["extract" [["function" "count"] "value"]
      ["=" "name" "uptime_seconds"]
      ["group_by" "value"]]
     [{:value 4000 :count 1}
      {:value 6000 :count 1}]

     ["extract" [["function" "count"] "value"]
      ["group_by" "value"]]
     [{:value 4000 :count 1}
      {:value 6000 :count 1}]

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

(deftest-http-app structured-fact-queries
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
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp reference-time
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :producer "bar1"})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :producer "bar2"})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time
                             :producer "bar3"})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time
                             :producer "bar4"})
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

(deftest-http-app structured-fact-queries-part-2
  [method [:get :post]]
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
                                      "b2" 3.14
                                      "c" ["b" "b" "c"]
                                      "d" {"n" ""}
                                      "e" "1"
                                      }
                "domain" "testing.com"
                "uptime_seconds" 6000}
        facts3 {"my_structured_fact" {"a" 2
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
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-certname! "foo4")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp reference-time
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :producer "bar1"})
      (scf-store/add-facts! {:certname  "foo2"
                             :values facts2
                             :timestamp (to-timestamp "2013-01-01")
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :producer "bar2"})
      (scf-store/add-facts! {:certname "foo3"
                             :values facts3
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time
                             :producer "bar3"})
      (scf-store/add-facts! {:certname "foo4"
                             :values facts4
                             :timestamp reference-time
                             :environment "PROD"
                             :producer_timestamp reference-time
                             :producer "bar4"})
      (scf-store/deactivate-node! "foo4"))

    (testing "pql query with regex and array paths should not fail"
      (let [response (query-response method "/v4" "inventory[certname] { facts.my_structured_fact.c.match(\"\\d+\") = 'a' }")]
        (is (= ["foo1" "foo3"]
               (map :certname (parse-result (:body response)))))))

    (testing "pql query with regex and non-array paths with numbers in the strings should not fail"
      (let [response (query-response method "/v4" "inventory[certname] { facts.my_structured_fact.b2 = 3.14 }")]
        (is (= ["foo2"]
               (map :certname (parse-result (:body response)))))))

    (testing "simple pql structured facts queries should not fail"
      (let [response (query-response method "/v4" "inventory[certname] { facts.my_structured_fact.a = 1 }")]
        (is (= ["foo1" "foo2"]
               (map :certname (parse-result (:body response)))))))))

;; FACT-CONTENTS TESTS
(deftest-http-app fact-contents-result-munging
  [[_version endpoint] fact-contents-endpoints
   method [:get :post]]
  (let [facts1 {"\"foo" "bar"
                "baz" {"1" "foo"}
                "\"bar\"" {1 "foo"}
                "foo#~bar" "baz"}]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-facts! {:certname "foo1"
                             :values facts1
                             :timestamp reference-time
                             :environment "DEV"
                             :producer_timestamp reference-time
                             :producer "bar1"}))

    (let [result (query-result method endpoint ["=" "certname" "foo1"])]
      (is (= (set (map :name result))
             #{"\"foo" "baz" "\"bar\"" "foo#~bar"})))))

(defn fact-content-response [method endpoint order-by-map]
  (fn [req]
    (-> (query-response method endpoint req order-by-map)
        :body
        slurp
        json/parse-string)))

(deftest-http-app factset-with-package-inventory-queries
  [[version endpoint] factsets-endpoints
   method [:get :post]]
  (populate-for-structured-tests reference-time)

  (testing "query with only include_package_inventory param should not fail"
    (let [response (query-response method endpoint nil {:include_package_inventory true})]
      (assert-success! response)
      (slurp (:body response))))

  (testing "factsets with package inventory query should ignore deactivated nodes"
    (let [responses (json/parse-string (slurp (:body (query-response
                                                       method endpoint))))]
      (is (not-any? #(= "foo4" %) (map #(get % "certname") responses)))))

  (testing "factset queries should return appropriate results"
    (let [queries [["=" "certname" "foo1"]
                   ["=" "environment" "DEV"]
                   ["<" "timestamp" "2014-01-01"]
                   ["<" "producer_timestamp" "2014-01-01"]
                   ["extract" ["certname" "hash"]
                   ["=" "certname" "foo1"]]
                   ["=" "producer" "bar2"]]
          responses (map (comp json/parse-string
                               slurp
                               :body
                               #(query-response method endpoint % {:include_package_inventory true}))
                         queries)]
      (is (= (munge-factset-response (into {} (first responses)))
             (munge-factset-response
              {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo1/facts")
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
               "producer" "bar1"
               "package_inventory" [["package1" "1.2.3" "provider1"]
                                    ["package2" "3.2.1" "provider1"]
                                    ["package3" "5" "provider2"]]
               "environment" "DEV"
               "certname" "foo1"
               "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"})))
      (is (= (munge-factsets-response (into [] (second responses)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo1/facts")
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
                    "producer" "bar1"
                    "package_inventory" [["package1" "1.2.3" "provider1"]
                                         ["package2" "3.2.1" "provider1"]
                                         ["package3" "5" "provider2"]]
                    "environment" "DEV"
                    "certname" "foo1"
                    "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"}

                   {"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "package_inventory" [["package1" "1.2.3" "provider1"]
                                         ["package2" "3.2.1" "provider1"]
                                         ["package3" "5" "provider2"]]
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))

      (is (= (munge-factsets-response (into [] (nth responses 2)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "package_inventory" [["package1" "1.2.3" "provider1"]
                                         ["package2" "3.2.1" "provider1"]
                                         ["package3" "5" "provider2"]]
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))

      (is (= (munge-factsets-response (into [] (nth responses 3)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "package_inventory" [["package1" "1.2.3" "provider1"]
                                         ["package2" "3.2.1" "provider1"]
                                         ["package3" "5" "provider2"]]
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}])))
      (is (= (into [] (nth responses 4))
             [{"certname" "foo1"
               "hash" "e9e19bca48ab42c44687822415df77b47fa41e64"}]))

      (is (= (munge-factsets-response (into [] (nth responses 5)))
             (map munge-factset-response
                  [{"facts" {"href" (str "/pdb/query/" (name version) "/factsets/foo2/facts")
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
                    "producer" "bar2"
                    "package_inventory" [["package1" "1.2.3" "provider1"]
                                         ["package2" "3.2.1" "provider1"]
                                         ["package3" "5" "provider2"]]
                    "environment" "DEV"
                    "certname" "foo2"
                    "hash" "39ad058afe565c797e925a862394d1bf457cf592"}]))))))

(deftest-http-app fact-contents-queries
  [[_version endpoint] fact-contents-endpoints
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
      (is (= #{{"certname" "foo1", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
               {"certname" "foo2", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "DEV"}
               {"certname" "foo3", "name" "my_structured_fact" "path" ["my_structured_fact" "a"], "value" 1, "environment" "PROD"}}
             (into #{} (response ["~>" "path" ["my_structured_fact" "^a"]]))))
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
      (is (= (into [] (response ["null?" "value" true]))
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

(deftest-http-app to-string-function-with-mask
  [[_version endpoint] [[:v4 v4-facts-endpoint]
                       [:v4 "/v4/fact-contents"]]
   method [:get :post]]
   (populate-for-structured-tests reference-time)

     (is (= (query-result method endpoint
                          ["extract" [["function" "to_string" "value" "FM9999"]]
                           ["and"
                            ["=" "name" "uptime_seconds"]
                            ["=" "certname" "foo1"]]])
            #{{:to_string "4000"}})))

(def no-parent-endpoints [[:v4 "/v4/factsets/foo/facts"]])

(deftest-http-app unknown-parent-handling
  [[_version endpoint] no-parent-endpoints
   method [:get :post]]

  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= HttpURLConnection/HTTP_NOT_FOUND status))
    (is (= {:error "No information is known about factset foo"} (json/parse-string body true)))))

(deftest-http-app no-certname-entity-test
  []
  (is-query-result :get "/v4" ["from" "fact_paths"] #{}))

(deftest developer-pretty-print
  (let [facts-body (fn [pretty?]
                     (with-test-db
                       (call-with-puppetdb-instance
                        (-> (svc-utils/create-temp-config)
                            (assoc :database *db*)
                            (assoc :read-database *read-db*)
                            (assoc-in [:developer :pretty-print] (str pretty?)))
                        (fn []
                          (let [facts {:certname "foo"
                                       :timestamp (now)
                                       :environment "DEV"
                                       :producer_timestamp (now)
                                       :producer "bar"
                                       :values{"foo" "bar"
                                               "baz" "bax"}}]
                            (jdbc/with-transacted-connection *db*
                              (scf-store/add-certname! "foo")
                              (scf-store/add-facts! facts))
                            (-> (svc-utils/query-url-str "/facts")
                                svc-utils/get-unparsed
                                :body))))))]
    (is (not (re-find #"\n\}, \{\n" (facts-body false))))
    (is (re-find #"\n\}, \{\n" (facts-body true)))))
