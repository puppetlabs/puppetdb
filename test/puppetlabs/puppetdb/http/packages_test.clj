(ns puppetlabs.puppetdb.http.packages-test
  (:require  [clojure.test :refer :all]
             [puppetlabs.puppetdb.testutils :refer [get-request parse-result]]
             [cheshire.core :as json]
             [puppetlabs.puppetdb.testutils.http :refer [*app* deftest-http-app]]
             [puppetlabs.puppetdb.http :as http]
             [clj-time.core :refer [days ago]]
             [puppetlabs.puppetdb.jdbc :as jdbc]
             [puppetlabs.puppetdb.testutils.db :refer [*db*]]
             [puppetlabs.puppetdb.scf.storage :as scf-store]))

(defn is-query-result [endpoint query expected-results]
  (let [encoded-query (if (string? query) query (json/generate-string query))
        request (get-request endpoint encoded-query)
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count expected-results) (count actual-result)))
    (is (coll? actual-result))
    (is (= expected-results (set actual-result)))
    (is (= http/status-ok status))))

(defn package-map [certname package-name version provider]
  {:certname certname
   :package_name package-name
   :version version
   :provider provider})

(deftest-http-app package-query-test
  [method [:get :post]]
  (let [fact-cmd-1 {:certname "node1"
                    :values {"os" {"name" "Ubuntu", "family" "Debian"}}
                    :timestamp (-> 1 days ago)
                    :environment "DEV"
                    :producer_timestamp (-> 1 days ago)
                    :producer "producer"
                    :package_inventory [["foo" "1.2.3" "apt"]
                                        ["bar" "2.3.4" "apt"]
                                        ["baz" "3.4.5" "apt"]]}
        fact-cmd-2 {:certname "node2"
                    :values {"os" {"name" "Debian", "family" "Debian"}}
                    :timestamp (-> 1 days ago)
                    :environment "DEV"
                    :producer_timestamp (-> 1 days ago)
                    :producer "producer"
                    :package_inventory [["foo" "1.2.3" "apt"]
                                        ["bar" "2.3.4" "apt"]]}]
    (jdbc/with-transacted-connection *db*
      (scf-store/add-certname! "node1")
      (scf-store/add-certname! "node2")
      (scf-store/add-facts! fact-cmd-1)
      (scf-store/add-facts! fact-cmd-2))

    (testing "by certname"
      (is-query-result "/v4"
                       ["from" "packages" ["=" "certname" "node1"]]
                       #{(package-map "node1" "foo" "1.2.3" "apt")
                         (package-map "node1" "bar" "2.3.4" "apt")
                         (package-map "node1" "baz" "3.4.5" "apt")}))

    (testing "by certname, with pql"
      (is-query-result "/v4"
                       "packages { certname = 'node1' }"
                       #{(package-map "node1" "foo" "1.2.3" "apt")
                         (package-map "node1" "bar" "2.3.4" "apt")
                         (package-map "node1" "baz" "3.4.5" "apt")}))

    (testing "by package name and version"
      (is-query-result "/v4"
                       ["from" "packages" ["and"
                                           ["=" "package_name" "bar"]
                                           ["=" "version" "2.3.4"]]]
                       #{(package-map "node1" "bar" "2.3.4" "apt")
                         (package-map "node2" "bar" "2.3.4" "apt")}))

    (testing "by regex over package name"
      (is-query-result "/v4"
                       ["from" "packages"
                        ["extract" ["certname" "package_name" "version" "provider"]
                         ["and"
                          ["~" "package_name" "ba?"]]]]
                       #{(package-map "node1" "bar" "2.3.4" "apt")
                         (package-map "node1" "baz" "3.4.5" "apt")
                         (package-map "node2" "bar" "2.3.4" "apt")}))

    (testing "by package named, grouped over package and version with counts"
      (is-query-result "/v4"
                       ["from" "packages"
                        ["extract" ["package_name" "version" ["function" "count" "certname"]]
                         ["~" "package_name" "ba?"]
                         ["group_by" "package_name" "version"]]]
                       #{{:package_name "bar" :version "2.3.4" :count 2}
                         {:package_name "baz" :version "3.4.5" :count 1}}))

    (testing "On all ubuntu machines, what version of 'foo' to I have?"
      (is-query-result "/v4"
                       ["from" "packages"
                        ["extract" ["certname" "version"]
                         ["and"
                          ["=" "package_name" "foo"]
                          ["subquery" "fact_contents"
                           ["and"
                            ["~>" "path" ["os" "name"]]
                            ["=" "value" "Ubuntu"]]]]]]
                       #{{:certname "node1" :version "1.2.3"}}))))
