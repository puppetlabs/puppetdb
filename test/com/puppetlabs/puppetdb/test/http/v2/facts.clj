(ns com.puppetlabs.puppetdb.test.http.v2.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

(def c-t pl-http/json-response-content-type)

(defn get-request
  "Return a GET request against path, suitable as an argument to a ring
  app. Params supported are content-type and query-string."
  ([path] (get-request path {}))
  ([path params]
   (let [request (request :get path params)]
     (update-in request [:headers] assoc "Accept" c-t))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/v2/facts" query))))

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
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now))
      (scf-store/add-facts! "foo3" facts3 (now))
      (scf-store/add-facts! "foo4" facts3 (now))
      (scf-store/deactivate-node! "foo4"))

    (testing "query without param should not fail"
      (let [response (get-response)
            body     (get response :body "null")]
        (is (= 200 (:status response)))))

    (testing "fact queries"
      (testing "well-formed queries"
        (doseq [[query result] {nil
                                [{:certname "foo1" :name "domain" :value "testing.com"}
                                 {:certname "foo1" :name "hostname" :value "foo1"}
                                 {:certname "foo1" :name "kernel" :value "Linux"}
                                 {:certname "foo1" :name "operatingsystem" :value "Debian"}
                                 {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:certname "foo1" :name "uptime_seconds" :value "4000"}
                                 {:certname "foo2" :name "domain" :value "testing.com"}
                                 {:certname "foo2" :name "hostname" :value "foo2"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "operatingsystem" :value "RedHat"}
                                 {:certname "foo2" :name "uptime_seconds" :value "6000"}
                                 {:certname "foo3" :name "domain" :value "testing.com"}
                                 {:certname "foo3" :name "hostname" :value "foo3"}
                                 {:certname "foo3" :name "kernel" :value "Darwin"}
                                 {:certname "foo3" :name "operatingsystem" :value "Darwin"}]

                                ["=" "name" "domain"]
                                [{:certname "foo1" :name "domain" :value "testing.com"}
                                 {:certname "foo2" :name "domain" :value "testing.com"}
                                 {:certname "foo3" :name "domain" :value "testing.com"}]

                                ["=" "value" "Darwin"]
                                [{:certname "foo3" :name "kernel" :value "Darwin"}
                                 {:certname "foo3" :name "operatingsystem" :value "Darwin"}]

                                ["not" ["=" "name" "domain"]]
                                [{:certname "foo1" :name "hostname" :value "foo1"}
                                 {:certname "foo1" :name "kernel" :value "Linux"}
                                 {:certname "foo1" :name "operatingsystem" :value "Debian"}
                                 {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:certname "foo1" :name "uptime_seconds" :value "4000"}
                                 {:certname "foo2" :name "hostname" :value "foo2"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "operatingsystem" :value "RedHat"}
                                 {:certname "foo2" :name "uptime_seconds" :value "6000"}
                                 {:certname "foo3" :name "hostname" :value "foo3"}
                                 {:certname "foo3" :name "kernel" :value "Darwin"}
                                 {:certname "foo3" :name "operatingsystem" :value "Darwin"}]

                                ["and" ["=" "name" "uptime_seconds"]
                                 [">" "value" "5000"]]
                                [{:certname "foo2" :name "uptime_seconds" :value "6000"}]

                                ["and" ["=" "name" "kernel"]
                                 ["~" "value" "i.u[xX]"]]
                                [{:certname "foo1" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}]

                                ["~" "name" "^ho\\wt.*e$"]
                                [{:certname "foo1" :name "hostname" :value "foo1"}
                                 {:certname "foo2" :name "hostname" :value "foo2"}
                                 {:certname "foo3" :name "hostname" :value "foo3"}]

                                ;; heinous regular expression to detect semvers
                                ["~" "value" "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"]
                                [{:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a"}]

                                ["and" ["=" "name" "hostname"]
                                 ["~" "certname" "^foo[12]$"]]
                                [{:certname "foo1" :name "hostname" :value "foo1"}
                                 {:certname "foo2" :name "hostname" :value "foo2"}]

                                ["and" ["=" "name" "hostname"]
                                 ["not" ["~" "certname" "^foo[12]$"]]]
                                [{:certname "foo3" :name "hostname" :value "foo3"}]

                                ["and" ["=" "name" "uptime_seconds"]
                                 [">=" "value" "4000"]
                                 ["<" "value" "6000.0"]]
                                [{:certname "foo1" :name "uptime_seconds" :value "4000"}]

                                ["and" ["=" "name" "domain"]
                                 [">" "value" "5000"]]
                                []

                                ["or" ["=" "name" "kernel"]
                                 ["=" "name" "operatingsystem"]]
                                [{:certname "foo1" :name "kernel" :value "Linux"}
                                 {:certname "foo1" :name "operatingsystem" :value "Debian"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "operatingsystem" :value "RedHat"}
                                 {:certname "foo3" :name "kernel" :value "Darwin"}
                                 {:certname "foo3" :name "operatingsystem" :value "Darwin"}]

                                ["=" "certname" "foo2"]
                                [{:certname "foo2" :name "domain" :value "testing.com" }
                                 {:certname "foo2" :name "hostname" :value "foo2"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "operatingsystem" :value "RedHat"}
                                 {:certname "foo2" :name "uptime_seconds" :value "6000"}]

                                ["=" ["node" "active"] true]
                                [{:certname "foo1" :name "domain" :value "testing.com"}
                                 {:certname "foo1" :name "hostname" :value "foo1"}
                                 {:certname "foo1" :name "kernel" :value "Linux"}
                                 {:certname "foo1" :name "operatingsystem" :value "Debian"}
                                 {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:certname "foo1" :name "uptime_seconds" :value "4000"}
                                 {:certname "foo2" :name "domain" :value "testing.com"}
                                 {:certname "foo2" :name "hostname" :value "foo2"}
                                 {:certname "foo2" :name "kernel" :value "Linux"}
                                 {:certname "foo2" :name "operatingsystem" :value "RedHat"}
                                 {:certname "foo2" :name "uptime_seconds" :value "6000"}
                                 {:certname "foo3" :name "domain" :value "testing.com"}
                                 {:certname "foo3" :name "hostname" :value "foo3"}
                                 {:certname "foo3" :name "kernel" :value "Darwin"}
                                 {:certname "foo3" :name "operatingsystem" :value "Darwin"}]

                                ;; Verify that we're enforcing that
                                ;; facts from inactive nodes are never
                                ;; returned, even if you ask for them
                                ;; specifically.
                                ["=" ["node" "active"] false]
                                []}]

          (let [request (get-request "/v2/facts" {"query" (json/generate-string query)})
                {:keys [status body headers]} (*app* request)]
            (is (= status pl-http/status-ok))
            (is (= (headers "Content-Type") c-t))
            (is (= result (json/parse-string body true))
                (pr-str query)))))

      (testing "malformed, yo"
        (let [request (get-request "/v2/facts" {"query" (json/generate-string [])})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body "[] is not well-formed: queries must contain at least one operator"))))

      (testing "'not' with too many arguments"
        (let [request (get-request "/v2/facts" {"query" (json/generate-string ["not" ["=" "name" "ipaddress"] ["=" "name" "operatingsystem"]])})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body "'not' takes exactly one argument, but 2 were supplied")))))))

(defn is-query-result
  [query results]
  (let [request (get-request "/v2/facts" {"query" (json/generate-string query)})
        {:keys [status body]} (*app* request)]
    (is (= status pl-http/status-ok))
    (is (= (try
             (json/parse-string body true)
             (catch Throwable e
               body)) results) query)))

(deftest fact-subqueries
  (testing "subqueries using a resource"
    (scf-store/add-certname! "foo")
    (scf-store/add-certname! "bar")
    (scf-store/add-certname! "baz")
    (scf-store/add-facts! "foo" {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000} (now))
    (scf-store/add-facts! "bar" {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12} (now))
    (scf-store/add-facts! "baz" {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000} (now))

    (let [catalog (:empty catalogs)
          apache-resource {:type "Class" :title "Apache"}
          apache-catalog (update-in catalog [:resources] conj {apache-resource (assoc apache-resource :exported false)})]
      (scf-store/replace-catalog! (assoc apache-catalog :certname "foo") (now))
      (scf-store/replace-catalog! (assoc apache-catalog :certname "bar") (now))
      (scf-store/replace-catalog! (assoc catalog :certname "baz") (now)))

    (testing "subqueries using a resource"
      (doseq [[query results]  {["and"
                                 ["=" "name" "ipaddress"]
                                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                         ["and"
                                                                                          ["=" "type" "Class"]
                                                                                          ["=" "title" "Apache"]]]]]]

                                [{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
                                 {:certname "foo" :name "ipaddress" :value "192.168.1.100"}]

                                ;; "not" matching resources
                                ["and"
                                 ["=" "name" "ipaddress"]
                                 ["not"
                                  ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                 ["and"
                                                                                  ["=" "type" "Class"]
                                                                                  ["=" "title" "Apache"]]]]]]]

                                [{:certname "baz" :name "ipaddress" :value "192.168.1.102"}]

                                ;; Multiple matching resources
                                ["and"
                                 ["=" "name" "ipaddress"]
                                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                ["=" "type" "Class"]]]]]

                                [{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
                                 {:certname "baz" :name "ipaddress" :value "192.168.1.102"}
                                 {:certname "foo" :name "ipaddress" :value "192.168.1.100"}]

                                ;; Multiple facts
                                ["and"
                                 ["or"
                                  ["=" "name" "ipaddress"]
                                  ["=" "name" "operatingsystem"]]
                                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                ["and"
                                                                                 ["=" "type" "Class"]
                                                                                 ["=" "title" "Apache"]]]]]]

                                [{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
                                 {:certname "bar" :name "operatingsystem" :value "Ubuntu"}
                                 {:certname "foo" :name "ipaddress" :value "192.168.1.100"}
                                 {:certname "foo" :name "operatingsystem" :value "Debian"}]

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

                                [{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
                                 {:certname "baz" :name "ipaddress" :value "192.168.1.102"}
                                 {:certname "foo" :name "ipaddress" :value "192.168.1.100"}]

                                ;; No matching resources
                                ["and"
                                 ["=" "name" "ipaddress"]
                                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                ["=" "type" "NotRealAtAll"]]]]]
                                []

                                ;; No matching facts
                                ["and"
                                 ["=" "name" "nosuchfact"]
                                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                                                ["=" "type" "Class"]]]]]
                                []

                                ;; Fact subquery
                                ["and"
                                 ["=" "name" "ipaddress"]
                                 ["in" "certname" ["extract" "certname" ["select-facts"
                                                                                ["and"
                                                                                 ["=" "name" "osfamily"]
                                                                                 ["=" "value" "Debian"]]]]]]

                                [{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
                                 {:certname "foo" :name "ipaddress" :value "192.168.1.100"}]

                                ;; Using a different column
                                ["in" "name" ["extract" "name" ["select-facts"
                                                                       ["=" "name" "osfamily"]]]]
                                [{:certname "bar" :name "osfamily" :value "Debian"}
                                 {:certname "baz" :name "osfamily" :value "RedHat"}
                                 {:certname "foo" :name "osfamily" :value "Debian"}]


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

                                [{:certname "foo" :name "ipaddress" :value "192.168.1.100"}]

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

                                [{:certname "foo" :name "ipaddress" :value "192.168.1.100"}]}]
        (is-query-result query results))))

  (testing "invalid queries"
    (doseq [[query msg] {["in" "certname" ["extract" "nothing" ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                         "Can't extract unknown resource field 'nothing'. Acceptable fields are: catalog, certname, exported, resource, sourcefile, sourceline, tags, title, type"

                         ["in" "nothing" ["extract" "certname" ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                         "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, name, value"}]
      (let [request (get-request "/v2/facts" {"query" (json/generate-string query)})
            {:keys [status body] :as result} (*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body msg))))))

(deftest fact-query-paging
  (testing "should not support paging-related query parameters"
    (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
      (let [request (get-request "/v2/facts" {k v})
            {:keys [status body]} (*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body (format "Unsupported query parameter '%s'" (name k))))))))
