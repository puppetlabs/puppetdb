(ns com.puppetlabs.puppetdb.test.http.facts
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [com.puppetlabs.puppetdb.http.server :as server]
            [clojure.java.io :as io]
            [flatland.ordered.map :as omap]
            [puppetlabs.kitchensink.core :as ks])
  (:use clojure.test
        ring.mock.request
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.puppetdb.examples]
        [clj-time.core :only [now]]
        [com.puppetlabs.puppetdb.testutils :only [get-request assert-success! paged-results paged-results*]]
        [com.puppetlabs.jdbc :only [with-transacted-connection]]))

(def v2-endpoint "/v2/facts")
(def v3-endpoint "/v3/facts")
(def v4-endpoint "/v4/facts")
(def endpoints [v2-endpoint v3-endpoint v4-endpoint])

(defixture super-fixture :each with-test-db with-http-app)

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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101"}}

   ;; "not" matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["not"
     ["in" "certname" ["extract" "certname" ["select-resources"
                                             ["and"
                                              ["=" "type" "Class"]
                                              ["=" "title" "Apache"]]]]]]]

   #{{:certname "baz" :name "ipaddress" :value "192.168.1.102"}}

   ;; Multiple matching resources
   ["and"
    ["=" "name" "ipaddress"]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["=" "type" "Class"]]]]]

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102"}}

   ;; Multiple facts
   ["and"
    ["or"
     ["=" "name" "ipaddress"]
     ["=" "name" "operatingsystem"]]
    ["in" "certname" ["extract" "certname" ["select-resources"
                                            ["and"
                                             ["=" "type" "Class"]
                                             ["=" "title" "Apache"]]]]]]

   #{{:certname "bar" :name "ipaddress" :value "192.168.1.101"}
     {:certname "bar" :name "operatingsystem" :value "Ubuntu"}
     {:certname "foo" :name "ipaddress" :value "192.168.1.100"}
     {:certname "foo" :name "operatingsystem" :value "Debian"}}

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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101"}
     {:certname "baz" :name "ipaddress" :value "192.168.1.102"}}

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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
     {:certname "bar" :name "ipaddress" :value "192.168.1.101"}}

   ;; Using a different column
   ["in" "name" ["extract" "name" ["select-facts"
                                   ["=" "name" "osfamily"]]]]

   #{{:certname "bar" :name "osfamily" :value "Debian"}
     {:certname "baz" :name "osfamily" :value "RedHat"}
     {:certname "foo" :name "osfamily" :value "Debian"}}

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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}}

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

   #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}}))

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

           #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
             {:certname "bar" :name "ipaddress" :value "192.168.1.101"}
             {:certname "baz" :name "ipaddress" :value "192.168.1.102"}}))

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

           #{{:certname "foo" :name "ipaddress" :value "192.168.1.100"}
             {:certname "bar" :name "ipaddress" :value "192.168.1.101"}
             {:certname "baz" :name "ipaddress" :value "192.168.1.102"}}))))

(def versioned-invalid-subqueries
  (omap/ordered-map
   "/v2/facts" (omap/ordered-map
                ;; Extract using an invalid field should throw an error
                ["in" "certname" ["extract" "nothing" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't extract unknown resource field 'nothing'. Acceptable fields are: catalog, certname, exported, resource, sourcefile, sourceline, tags, title, type"

                ;; In-query for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, name, value")

   "/v3/facts" (omap/ordered-map
                ;; Extract using an invalid fields should throw an error
                ["in" "certname" ["extract" "nothing" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't extract unknown resource field 'nothing'. Acceptable fields are: catalog, certname, exported, file, line, resource, tags, title, type"

                ;; Subqueries using old sourcefile/sourceline should throw error
                ["and"
                 ["=" "name" "ipaddress"]
                 ["in" "certname" ["extract" "certname" ["select-resources"
                                                         ["and"
                                                          ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                                                          ["=" "sourceline" 1]]]]]]

                "sourcefile is not a queryable object for resources"

                ;; In-queries for invalid fields should throw an error
                ["in" "nothing" ["extract" "certname" ["select-resources"
                                                       ["=" "type" "Class"]]]]
                "Can't match on unknown fact field 'nothing' for 'in'. Acceptable fields are: certname, name, value")))

(def common-well-formed-tests
  (omap/ordered-map
   nil
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
   []))

(defn test-app
  ([read-write-db]
     (test-app read-write-db read-write-db))
  ([read-db write-db]
     (server/build-app
      :globals {:scf-read-db          read-db
                :scf-write-db         write-db
                :command-mq           *mq*
                :event-query-limit    20000
                :product-name         "puppetdb"})))

(defn with-shutdown-after [dbs f]
  (f)
  (doseq [db dbs]
    (sql/with-connection db
      (sql/do-commands "SHUTDOWN"))))

(deftest fact-queries
  (doseq [endpoint endpoints]
    (super-fixture
     (fn []
       (testing (str "fact queries for " endpoint ":")
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
                   (is (= (set result) (set (json/parse-string (slurp body) true)))))))

             (testing "malformed, yo"
               (let [request (get-request endpoint (json/generate-string []))
                     {:keys [status body]} (*app* request)]
                 (is (= status pl-http/status-bad-request))
                 (is (= body "[] is not well-formed: queries must contain at least one operator"))))

             (testing "'not' with too many arguments"
               (let [request (get-request endpoint (json/generate-string ["not" ["=" "name" "ipaddress"] ["=" "name" "operatingsystem"]]))
                     {:keys [status body]} (*app* request)]
                 (is (= status pl-http/status-bad-request))
                 (is (= body "'not' takes exactly one argument, but 2 were supplied")))))))))))


(deftest fact-subqueries
  (doseq [endpoint endpoints]
    (super-fixture
     (fn []
       (testing (str "subqueries: valid for endpoint " endpoint ":")
         (scf-store/add-certname! "foo")
         (scf-store/add-certname! "bar")
         (scf-store/add-certname! "baz")
         (scf-store/add-facts! "foo" {"ipaddress" "192.168.1.100" "operatingsystem" "Debian" "osfamily" "Debian" "uptime_seconds" 11000} (now) "DEV")
         (scf-store/add-facts! "bar" {"ipaddress" "192.168.1.101" "operatingsystem" "Ubuntu" "osfamily" "Debian" "uptime_seconds" 12} (now) "DEV")
         (scf-store/add-facts! "baz" {"ipaddress" "192.168.1.102" "operatingsystem" "CentOS" "osfamily" "RedHat" "uptime_seconds" 50000} (now) "DEV")

         (let [catalog (:empty catalogs)
               apache-resource {:type "Class" :title "Apache"}
               apache-catalog (update-in catalog [:resources] conj {apache-resource (assoc apache-resource :exported false)})]
           (scf-store/replace-catalog! (assoc apache-catalog :certname "foo") (now))
           (scf-store/replace-catalog! (assoc apache-catalog :certname "bar") (now))
           (scf-store/replace-catalog! (assoc catalog :certname "baz") (now)))

         (doseq [[query results] (get versioned-subqueries endpoint)]
           (testing (str "query: " query " should match expected output")
             (is-query-result endpoint query results))))

       (testing "subqueries: invalid"
         (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
           (testing (str "query: " query " should fail with msg: " msg)
             (let [request (get-request endpoint (json/generate-string query))
                   {:keys [status body] :as result} (*app* request)]
               (is (= body msg))
               (is (= status pl-http/status-bad-request))))))))))

(deftest ^{:postgres false} two-database-fact-query-config
  (doseq [endpoint endpoints]
    (super-fixture
     (fn []
       (testing (str "endpoint " endpoint ":")
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
                     (is (= [{:certname "foo1" :name "domain" :value "testing.com"}
                             {:certname "foo1" :name "hostname" :value "foo1"}
                             {:certname "foo1" :name "kernel" :value "Linux"}
                             {:certname "foo1" :name "operatingsystem" :value "Debian"}
                             {:certname "foo1" :name "some_version" :value "1.3.7+build.11.e0f985a"}
                             {:certname "foo1" :name "uptime_seconds" :value "4000"}]
                            (json/parse-stream (io/reader body) true))))))))))))))


(defn test-paged-results
  [query limit total count?]
  (paged-results
    {:app-fn  *app*
     :path    v3-endpoint
     :query   query
     :limit   limit
     :total   total
     :include-total  count?}))

(deftest fact-query-paging
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

    (testing "v3 should support fact paging"
      (doseq [[label counts?] [["without" false]
                               ["with" true]]]
        (testing (str "should support paging through facts " label " counts")
          (let [results (test-paged-results
                         ["=" "certname" "foo1"]
                         2 (count facts1) counts?)]
            (is (= (count facts1) (count results)))
            (is (= (set (map (fn [[k v]]
                               {:certname "foo1"
                                :name     k
                                :value    v})
                             facts1))
                   (set results)))))))

    (testing "should not support paging-related query parameters"
      (doseq [[k v] {:limit 10 :offset 10 :order-by [{:field "foo"}]}]
        (let [request (get-request v2-endpoint nil {k v})
              {:keys [status body]} (*app* request)]
          (is (= status pl-http/status-bad-request))
          (is (= body (format "Unsupported query parameter '%s'" (name k)))))))))



(defn- raw-query-facts
  [query paging-options]

  (let [{:keys [limit offset include-total]
         :or {limit Integer/MAX_VALUE
              include-total true
              offset 0}}  paging-options

              {:keys [headers body]} (paged-results* (assoc paging-options
                                                       :app-fn  *app*
                                                       :path    v3-endpoint
                                                       :offset  offset
                                                       :limit   limit
                                                       :include-total include-total))]
    {:results body
     :count (when-let [rec-count (get headers "X-Records")]
              (ks/parse-int rec-count))}))

(defn- query-facts
  [paging-options]
  (:results (raw-query-facts nil paging-options)))

(deftest paging-results
  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host"}
        f2         {:certname "b.local" :name "uptime_days" :value "4"}
        f3         {:certname "c.local" :name "hostname"    :value "c-host"}
        f4         {:certname "d.local" :name "uptime_days" :value "2"}
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
      (let [actual (:count (raw-query-facts nil {:include-total true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-facts {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order-by.*"
                        (:body (*app* (get-request "/v3/facts" nil {:order-by (json/generate-string [{"field" "invalid-field" "order" "ASC"}])}))))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4]]
                                  ["DESC" [f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[name-order value-order] expected] [[["DESC" "ASC"]  [f4 f2 f1 f3]]
                                                     [["DESC" "DESC"] [f2 f4 f3 f1]]
                                                     [["ASC" "DESC"]  [f3 f1 f2 f4]]
                                                     [["ASC" "ASC"]   [f1 f3 f4 f2]]]]
          (testing (format "name %s value %s" name-order value-order)
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "name" "order" name-order}
                                                                     {"field" "value" "order" value-order}])}})]
              (is (= actual expected)))))))

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
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}
                           :offset offset})]
              (is (= actual expected)))))))))
