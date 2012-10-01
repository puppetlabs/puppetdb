(ns com.puppetlabs.puppetdb.test.http.v2.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
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
          (is (= body "[] is not well-formed; queries must contain at least one operator")))))))
