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

(def c-t "application/json")

(defn make-request
  "Return a GET request against path, suitable as an argument to a ring
  app. Params supported are content-type and query-string."
  ([path] (make-request path {}))
  ([path params]
   (let [request (request :get path params)]
     (update-in request [:headers] assoc "Accept" c-t))))

(deftest fact-set-handler
  (let [certname-with-facts "got_facts"
        certname-without-facts "no_facts"
        facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! certname-without-facts)
      (scf-store/add-certname! certname-with-facts)
      (scf-store/add-facts! certname-with-facts facts (now)))

    (testing "for an absent node"
      (let [request (make-request "/v2/facts/imaginary_node")
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error "Could not find facts for imaginary_node"}))))

    (testing "for a present node without facts"
      (let [request (make-request (format "/v2/facts/%s" certname-without-facts))
            response (*app* request)]
        (is (= (:status response) pl-http/status-not-found))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (json/parse-string (:body response) true)
               {:error (str "Could not find facts for " certname-without-facts)}))))

    (testing "for a present node with facts"
      (let [request (make-request (format "/v2/facts/%s" certname-with-facts))
            response (*app* request)]
        (is (= (:status response) pl-http/status-ok))
        (is (= (get-in response [:headers "Content-Type"]) c-t))
        (is (= (set (json/parse-string (:body response) true))
               (set (for [[fact value] facts]
                      {:node certname-with-facts :fact fact :value value}))))))))

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
                "operatingsystem" "Darwin"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-certname! "foo3")
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now))
      (scf-store/add-facts! "foo3" facts3 (now))
      (scf-store/deactivate-node! "foo1"))

    (testing "fact queries"
      (testing "well-formed queries"
        (doseq [[query result] {nil
                                [{:node "foo1" :fact "domain" :value "testing.com"}
                                 {:node "foo1" :fact "hostname" :value "foo1"}
                                 {:node "foo1" :fact "kernel" :value "Linux"}
                                 {:node "foo1" :fact "operatingsystem" :value "Debian"}
                                 {:node "foo1" :fact "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:node "foo1" :fact "uptime_seconds" :value "4000"}
                                 {:node "foo2" :fact "domain" :value "testing.com"}
                                 {:node "foo2" :fact "hostname" :value "foo2"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "operatingsystem" :value "RedHat"}
                                 {:node "foo2" :fact "uptime_seconds" :value "6000"}
                                 {:node "foo3" :fact "domain" :value "testing.com"}
                                 {:node "foo3" :fact "hostname" :value "foo3"}
                                 {:node "foo3" :fact "kernel" :value "Darwin"}
                                 {:node "foo3" :fact "operatingsystem" :value "Darwin"}]

                                ["=" ["fact" "name"] "domain"]
                                [{:node "foo1" :fact "domain" :value "testing.com"}
                                 {:node "foo2" :fact "domain" :value "testing.com"}
                                 {:node "foo3" :fact "domain" :value "testing.com"}]

                                ["=" ["fact" "value"] "Darwin"]
                                [{:node "foo3" :fact "kernel" :value "Darwin"}
                                 {:node "foo3" :fact "operatingsystem" :value "Darwin"}]

                                ["not" ["=" ["fact" "name"] "domain"]]
                                [{:node "foo1" :fact "hostname" :value "foo1"}
                                 {:node "foo1" :fact "kernel" :value "Linux"}
                                 {:node "foo1" :fact "operatingsystem" :value "Debian"}
                                 {:node "foo1" :fact "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:node "foo1" :fact "uptime_seconds" :value "4000"}
                                 {:node "foo2" :fact "hostname" :value "foo2"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "operatingsystem" :value "RedHat"}
                                 {:node "foo2" :fact "uptime_seconds" :value "6000"}
                                 {:node "foo3" :fact "hostname" :value "foo3"}
                                 {:node "foo3" :fact "kernel" :value "Darwin"}
                                 {:node "foo3" :fact "operatingsystem" :value "Darwin"}]

                                ["and" ["=" ["fact" "name"] "uptime_seconds"]
                                 [">" ["fact" "value"] "5000"]]
                                [{:node "foo2" :fact "uptime_seconds" :value "6000"}]

                                ["and" ["=" ["fact" "name"] "kernel"]
                                 ["~" ["fact" "value"] "i.u[xX]"]]
                                [{:node "foo1" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}]

                                ["~" ["fact" "name"] "^ho\\wt.*e$"]
                                [{:node "foo1" :fact "hostname" :value "foo1"}
                                 {:node "foo2" :fact "hostname" :value "foo2"}
                                 {:node "foo3" :fact "hostname" :value "foo3"}]

                                ;; heinous regular expression to detect semvers
                                ["~" ["fact" "value"] "^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$"]
                                [{:node "foo1" :fact "some_version" :value "1.3.7+build.11.e0f985a"}]

                                ["and" ["=" ["fact" "name"] "hostname"]
                                 ["~" ["node" "name"] "^foo[12]$"]]
                                [{:node "foo1" :fact "hostname" :value "foo1"}
                                 {:node "foo2" :fact "hostname" :value "foo2"}]

                                ["and" ["=" ["fact" "name"] "hostname"]
                                 ["not" ["~" ["node" "name"] "^foo[12]$"]]]
                                [{:node "foo3" :fact "hostname" :value "foo3"}]

                                ["and" ["=" ["fact" "name"] "uptime_seconds"]
                                 [">=" ["fact" "value"] "4000"]
                                 ["<" ["fact" "value"] "6000.0"]]
                                [{:node "foo1" :fact "uptime_seconds" :value "4000"}]

                                ["and" ["=" ["fact" "name"] "domain"]
                                 [">" ["fact" "value"] "5000"]]
                                []

                                ["or" ["=" ["fact" "name"] "kernel"]
                                 ["=" ["fact" "name"] "operatingsystem"]]
                                [{:node "foo1" :fact "kernel" :value "Linux"}
                                 {:node "foo1" :fact "operatingsystem" :value "Debian"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "operatingsystem" :value "RedHat"}
                                 {:node "foo3" :fact "kernel" :value "Darwin"}
                                 {:node "foo3" :fact "operatingsystem" :value "Darwin"}]

                                ["=" ["node" "name"] "foo2"]
                                [{:node "foo2" :fact "domain" :value "testing.com" }
                                 {:node "foo2" :fact "hostname" :value "foo2"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "operatingsystem" :value "RedHat"}
                                 {:node "foo2" :fact "uptime_seconds" :value "6000"}]

                                ["=" ["node" "active"] true]
                                [{:node "foo2" :fact "domain" :value "testing.com"}
                                 {:node "foo2" :fact "hostname" :value "foo2"}
                                 {:node "foo2" :fact "kernel" :value "Linux"}
                                 {:node "foo2" :fact "operatingsystem" :value "RedHat"}
                                 {:node "foo2" :fact "uptime_seconds" :value "6000"}
                                 {:node "foo3" :fact "domain" :value "testing.com"}
                                 {:node "foo3" :fact "hostname" :value "foo3"}
                                 {:node "foo3" :fact "kernel" :value "Darwin"}
                                 {:node "foo3" :fact "operatingsystem" :value "Darwin"}]

                                ["=" ["node" "active"] false]
                                [{:node "foo1" :fact "domain" :value "testing.com"}
                                 {:node "foo1" :fact "hostname" :value "foo1"}
                                 {:node "foo1" :fact "kernel" :value "Linux"}
                                 {:node "foo1" :fact "operatingsystem" :value "Debian"}
                                 {:node "foo1" :fact "some_version" :value "1.3.7+build.11.e0f985a"}
                                 {:node "foo1" :fact "uptime_seconds" :value "4000"}]

                                ["and" ["=" ["node" "name"] "foo1"]
                                 ["=" ["node" "active"] true]]
                                []}]
          (let [request (make-request "/v2/facts" {"query" (json/generate-string query)})
                {:keys [status body headers]} (*app* request)]
            (is (= status pl-http/status-ok))
            (is (= (headers "Content-Type") c-t))
            (is (= result (json/parse-string body true))
                (pr-str query)))))

      (testing "malformed, yo"
        (let [request (make-request "/v2/facts" {"query" (json/generate-string [])})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body "[] is not well-formed: queries must contain at least one operator")))))))

(defn is-query-result
  [query results]
  (let [request (make-request "/v2/facts" {"query" (json/generate-string query)})
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
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                         ["and"
                                                                                          ["=" "type" "Class"]
                                                                                          ["=" "title" "Apache"]]]]]]

                                [{:node "bar" :fact "ipaddress" :value "192.168.1.101"}
                                 {:node "foo" :fact "ipaddress" :value "192.168.1.100"}]

                                ;; "not" matching resources
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["not"
                                  ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                          ["and"
                                                                                           ["=" "type" "Class"]
                                                                                           ["=" "title" "Apache"]]]]]]]

                                [{:node "baz" :fact "ipaddress" :value "192.168.1.102"}]

                                ;; Multiple matching resources
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                         ["=" "type" "Class"]]]]]

                                [{:node "bar" :fact "ipaddress" :value "192.168.1.101"}
                                 {:node "baz" :fact "ipaddress" :value "192.168.1.102"}
                                 {:node "foo" :fact "ipaddress" :value "192.168.1.100"}]

                                ;; Multiple facts
                                ["and"
                                 ["or"
                                  ["=" ["fact" "name"] "ipaddress"]
                                  ["=" ["fact" "name"] "operatingsystem"]]
                                 ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                         ["and"
                                                                                          ["=" "type" "Class"]
                                                                                          ["=" "title" "Apache"]]]]]]

                                [{:node "bar" :fact "ipaddress" :value "192.168.1.101"}
                                 {:node "bar" :fact "operatingsystem" :value "Ubuntu"}
                                 {:node "foo" :fact "ipaddress" :value "192.168.1.100"}
                                 {:node "foo" :fact "operatingsystem" :value "Debian"}]

                                ;; Multiple subqueries
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["or"
                                  ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                          ["and"
                                                                                           ["=" "type" "Class"]
                                                                                           ["=" "title" "Apache"]]]]]
                                  ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                          ["and"
                                                                                           ["=" "type" "Class"]
                                                                                           ["=" "title" "Main"]]]]]]]

                                [{:node "bar" :fact "ipaddress" :value "192.168.1.101"}
                                 {:node "baz" :fact "ipaddress" :value "192.168.1.102"}
                                 {:node "foo" :fact "ipaddress" :value "192.168.1.100"}]

                                ;; No matching resources
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                         ["=" "type" "NotRealAtAll"]]]]]

                                []

                                ;; No matching facts
                                ["and"
                                 ["=" ["fact" "name"] "nosuchfact"]
                                 ["in-result" "certname" ["project" "certname" ["select-resources"
                                                                                         ["=" "type" "Class"]]]]]

                                []

                                ;; Fact subquery
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-facts"
                                                                                     ["and"
                                                                                      ["=" ["fact" "name"] "osfamily"]
                                                                                      ["=" ["fact" "value"] "Debian"]]]]]]

                                [{:node "bar" :fact "ipaddress" :value "192.168.1.101"}
                                 {:node "foo" :fact "ipaddress" :value "192.168.1.100"}]

                                ;; Nested fact subqueries
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-facts"
                                                                                     ["and"
                                                                                      ["=" ["fact" "name"] "osfamily"]
                                                                                      ["=" ["fact" "value"] "Debian"]
                                                                                      ["in-result" "certname" ["project" "certname" ["select-facts"
                                                                                                                                          ["and"
                                                                                                                                           ["=" ["fact" "name"] "uptime_seconds"]
                                                                                                                                           [">" ["fact" "value"] 10000]]]]]]]]]]

                                [{:node "foo" :fact "ipaddress" :value "192.168.1.100"}]

                                ;; Multiple fact subqueries
                                ["and"
                                 ["=" ["fact" "name"] "ipaddress"]
                                 ["in-result" "certname" ["project" "certname" ["select-facts"
                                                                                     ["and"
                                                                                      ["=" ["fact" "name"] "osfamily"]
                                                                                      ["=" ["fact" "value"] "Debian"]]]]]
                                 ["in-result" "certname" ["project" "certname" ["select-facts"
                                                                                     ["and"
                                                                                      ["=" ["fact" "name"] "uptime_seconds"]
                                                                                      [">" ["fact" "value"] 10000]]]]]]

                                [{:node "foo" :fact "ipaddress" :value "192.168.1.100"}]}]
        (is-query-result query results))))

  (testing "invalid queries"
    (doseq [[query msg] {["in-result" "certname" ["project" "nothing" ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                         "Can't project unknown resource field 'nothing'. Acceptable fields are: catalog, certname, exported, resource, sourcefile, sourceline, tags, title, type"

                         ["in-result" "nothing" ["project" "certname" ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                         "Can't match on unknown fact field 'nothing' for 'in-result'. Acceptable fields are: certname, fact, value"}]
      (let [request (make-request "/v2/facts" {"query" (json/generate-string query)})
            {:keys [status body] :as result} (*app* request)]
        (is (= status pl-http/status-bad-request))
        (is (= body msg))))))
