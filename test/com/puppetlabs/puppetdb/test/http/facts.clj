(ns com.puppetlabs.puppetdb.test.http.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.puppetdb.http.server :as server]
            [clojure.java.io :as io]
            [flatland.ordered.map :as omap]
            [puppetlabs.kitchensink.core :as ks]
            [com.puppetlabs.puppetdb.query :refer [remove-all-environments]]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [com.puppetlabs.puppetdb.fixtures :refer :all]
            [com.puppetlabs.puppetdb.examples :refer :all]
            [clj-time.core :refer [now]]
            [com.puppetlabs.puppetdb.testutils :refer [get-request assert-success!
                                                      paged-results paged-results*
                                                      deftestseq]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection]]))

(def v2-endpoint "/v2/facts")
(def v3-endpoint "/v3/facts")
(def v4-endpoint "/v4/facts")
(def v4-environment "/v4/environments/DEV/facts")
(def endpoints [[:v2 v2-endpoint]
                [:v3 v3-endpoint]
                [:v4 v4-endpoint]
                [:v4 v4-environment]])

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-response
  ([endpoint]      (get-response endpoint nil))
  ([endpoint query] (*app* (get-request endpoint query)))
  ([endpoint query params] (*app* (get-request endpoint query params))))

(defn parse-result
  "Stringify (if needed) then parse the response"
  [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Throwable e
      body)))

(defn is-query-result
  [endpoint query expected-results]
  (let [request (get-request endpoint (json/generate-string query))
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count actual-result) (count expected-results)))
    (is (= (set actual-result) expected-results))
    (is (= status pl-http/status-ok))))

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
             {:certname "baz" :name "ipaddress" :value "192.168.1.102" :environment "DEV"}}))))

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
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, environment, name, value")

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

                ;; In-queries for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, environment, name, value")))

(def common-well-formed-tests
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
    {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
    {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}
    {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}
    {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
    {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ["=" "name" "domain"]
   [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
    {:certname "foo2" :name "domain" :value "testing.com" :environment "DEV"}
    {:certname "foo3" :name "domain" :value "testing.com" :environment "DEV"}]

   ["=" "value" "Darwin"]
   [{:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ["not" ["=" "name" "domain"]]
   [{:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
    {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
    {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
    {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}
    {:certname "foo2" :name "hostname" :value "foo2" :environment "DEV"}
    {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
    {:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}
    {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
    {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ["and" ["=" "name" "uptime_seconds"]
    [">" "value" "5000"]]
   [{:certname "foo2" :name "uptime_seconds" :value "6000" :environment "DEV"}]

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

   ["and" ["=" "name" "uptime_seconds"]
    [">=" "value" "4000"]
    ["<" "value" "6000.0"]]
   [{:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}]

   ["and" ["=" "name" "domain"]
    [">" "value" "5000"]]
   []

   ["or" ["=" "name" "kernel"]
    ["=" "name" "operatingsystem"]]
   [{:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
    {:certname "foo2" :name "kernel" :value "Linux" :environment "DEV"}
    {:certname "foo2" :name "operatingsystem" :value "RedHat" :environment "DEV"}
    {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

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
    {:certname "foo3" :name "hostname" :value "foo3" :environment "DEV"}
    {:certname "foo3" :name "kernel" :value "Darwin" :environment "DEV"}
    {:certname "foo3" :name "operatingsystem" :value "Darwin" :environment "DEV"}]

   ;; Verify that we're enforcing that
   ;; facts from inactive nodes are never
   ;; returned, even if you ask for them
   ;; specifically.
   ["=" ["node" "active"] false]
   []))

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
  [[version endpoint] endpoints]

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
      (scf-store/add-facts! "foo1" facts1 (now) "DEV")
      (scf-store/add-facts! "foo2" facts2 (now) "DEV")
      (scf-store/add-facts! "foo3" facts3 (now) "DEV")
      (scf-store/add-facts! "foo4" facts3 (now) "DEV")
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (get-response endpoint)]
        (assert-success! response)
        (slurp (:body response))))

    (testing "fact queries"
      (testing "well-formed queries"
        (doseq [[query result] common-well-formed-tests]
          (let [request (get-request endpoint (json/generate-string query))
                {:keys [status body headers]} (*app* request)]
            (is (= status pl-http/status-ok))
            (is (= (headers "Content-Type") c-t))
            (is (= (set (remove-all-environments version result)) (set (json/parse-string (slurp body) true)))))))

      (testing "malformed, yo"
        (let [request (get-request endpoint (json/generate-string []))
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body "[] is not well-formed: queries must contain at least one operator"))))

      (testing "'not' with too many arguments"
        (let [request (get-request endpoint (json/generate-string ["not" ["=" "name" "ipaddress"] ["=" "name" "operatingsystem"]]))
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body "'not' takes exactly one argument, but 2 were supplied")))))))


(deftestseq fact-subqueries
  [[version endpoint] endpoints]

  (scf-store/add-certname! "foo")
  (scf-store/add-certname! "bar")
  (scf-store/add-certname! "baz")
  (scf-store/add-facts! "foo" {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000} (now) "DEV")
  (scf-store/add-facts! "bar" {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12} (now) "DEV")
  (scf-store/add-facts! "baz" {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000} (now) "DEV")

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
          (is (= status pl-http/status-bad-request)))))))

(deftestseq ^{:postgres false} two-database-fact-query-config
  [[version endpoint] endpoints]

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
            (scf-store/add-facts! "foo1" facts1 (now) "DEV"))

          (testing "queries only use the read database"
            (let [request (get-request endpoint (json/parse-string nil))
                  {:keys [status body headers]} (two-db-app request)]
              (is (= status pl-http/status-ok))
              (is (= (headers "Content-Type") c-t))
              (is (empty? (json/parse-stream (io/reader body) true)))))

          (testing "config with only a single database returns results"
            (let [request (get-request endpoint (json/parse-string nil))
                  {:keys [status body headers]} (one-db-app request)]
              (is (= status pl-http/status-ok))
              (is (= (headers "Content-Type") c-t))
              (is (= (remove-all-environments version
                                              [{:certname "foo1" :name "domain" :value "testing.com" :environment "DEV"}
                                               {:certname "foo1" :name "hostname" :value "foo1" :environment "DEV"}
                                               {:certname "foo1" :name "kernel" :value "Linux" :environment "DEV"}
                                               {:certname "foo1" :name "operatingsystem" :value "Debian" :environment "DEV"}
                                               {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a" :environment "DEV"}
                                               {:certname "foo1" :name "uptime_seconds" :value "4000" :environment "DEV"}])
                     (json/parse-stream (io/reader body) true))))))))))

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
  [[version endpoint] endpoints]

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
      (scf-store/add-facts! "foo1" facts1 (now) "DEV")
      (scf-store/add-facts! "foo2" facts2 (now) "DEV"))

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
            (is (= status pl-http/status-bad-request))
            (is (= body (format "Unsupported query parameter '%s'" (name k))))))))))

(defn- raw-query-facts
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

(defn- query-facts
  [endpoint paging-options]
  (:results (raw-query-facts endpoint nil paging-options)))

(deftestseq paging-results
  [[version endpoint] endpoints
   :when (not= version :v2)]

  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host" :environment "DEV"}
        f2         {:certname "b.local" :name "uptime_days" :value "4" :environment "DEV"}
        f3         {:certname "c.local" :name "hostname"    :value "c-host" :environment "DEV"}
        f4         {:certname "d.local" :name "uptime_days" :value "2" :environment "DEV"}
        fact-count 4]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! "c.local" {"hostname" "c-host"} (now) "DEV")
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! "a.local" {"hostname" "a-host"} (now) "DEV")
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! "d.local" {"uptime_days" "2"} (now) "DEV")
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! "b.local" {"uptime_days" "4"} (now) "DEV")

    (testing "include total results count"
      (let [actual (:count (raw-query-facts endpoint nil {:include-total true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-facts endpoint {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order-by.*"
                        (:body (*app* (get-request endpoint nil {:order-by (json/generate-string [{"field" "invalid-field" "order" "ASC"}])}))))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4]]
                                  ["DESC" [f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-facts endpoint
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}})]
              (is (= actual (remove-all-environments version expected)))))))

      (testing "multiple fields"
        (doseq [[[name-order value-order] expected] [[["DESC" "ASC"]  [f4 f2 f1 f3]]
                                                     [["DESC" "DESC"] [f2 f4 f3 f1]]
                                                     [["ASC" "DESC"]  [f3 f1 f2 f4]]
                                                     [["ASC" "ASC"]   [f1 f3 f4 f2]]]]
          (testing (format "name %s value %s" name-order value-order)
            (let [actual (query-facts endpoint
                          {:params {:order-by (json/generate-string [{"field" "name" "order" name-order}
                                                                     {"field" "value" "order" value-order}])}})]
              (is (= actual (remove-all-environments version expected))))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [f1 f2 f3 f4]]
                                                   [1 [f2 f3 f4]]
                                                   [2 [f3 f4]]
                                                   [3 [f4]]
                                                   [4 []]]]
                                          ["DESC" [[0 [f4 f3 f2 f1]]
                                                   [1 [f3 f2 f1]]
                                                   [2 [f2 f1]]
                                                   [3 [f1]]
                                                   [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-facts endpoint
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}
                           :offset offset})]
              (is (= actual (remove-all-environments version expected))))))))))


(deftestseq fact-environment-queries
  [[version endpoint] endpoints
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
        (scf-store/add-facts! "foo1" facts1 (now) "DEV")
        (scf-store/add-facts! "foo2" facts2 (now) "DEV")
        (scf-store/add-facts! "foo3" facts3 (now) "PROD")
        (scf-store/add-facts! "foo4" facts4 (now) "PROD"))

      (doseq [query '[[= environment PROD]
                      [not [= environment DEV]]
                      ["~" environment PR.*]
                      [not ["~" environment DE.*]]]]
        (let [{:keys [status headers body]} (*app* (get-request endpoint query))
              results (json/parse-string (slurp body) true)]
          (is (= status pl-http/status-ok))
          (is (= (headers "Content-Type") c-t))
          (is (= 9 (count results)))
          (is (every? #(= (:environment %) "PROD") results))
          (is (= #{"foo3" "foo4"} (set (map :certname results)))))))))

(deftest environment-query-failures
  (let [{:keys [status headers body]} (*app* (get-request v2-endpoint '[= environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a queryable object for version 2" body)))
  (let [{:keys [status headers body]} (*app* (get-request v3-endpoint '[= environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a queryable object for version 3" body)))
  (let [{:keys [status headers body]} (*app* (get-request v2-endpoint '["~" environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a valid version 2 operand" body)))
  (let [{:keys [status headers body]} (*app* (get-request v3-endpoint '["~" environment PROD]))]
    (is (= status 400))
    (is (re-find #"environment is not a valid version 3 operand" body))))
