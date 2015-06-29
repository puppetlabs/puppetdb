(ns puppetlabs.pe-puppetdb-extensions.sync.services-test
  (:import [org.joda.time Period])
  (:require [puppetlabs.pe-puppetdb-extensions.sync.services :refer :all]
            [clojure.test :refer :all]
            [slingshot.test :refer :all]
            [clj-time.core :refer [seconds]]
            [puppetlabs.puppetdb.time :refer [period? periods-equal? parse-period]]))

(deftest enable-periodic-sync?-test
  (testing "Happy case"
    (is (= true (enable-periodic-sync?
                 [{:server_url "http://foo.bar:8080", :interval (-> 12 seconds)}]))))

  (testing "Disable sync cases"
    (are [remote-config] (= false (enable-periodic-sync? remote-config))
      [{:server_url "http://foo.bar:8080", :interval (-> 0 seconds)}]
      [{:server_url "http://foo.bar:8080"}]
      []
      nil))

  (testing "Invalid sync configs"
    (are [remote-config] (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                                   (enable-periodic-sync? remote-config))

      [{:server_url "http://foo.bar:8080", :interval (-> -12 seconds)}])))

(defn periods-or-vals-equal? [x y]
  (if (period? x)
    (periods-equal? x y)
    (= x y)))

(deftest coerce-to-period-test
  (are [in out] (periods-or-vals-equal? out (coerce-to-period in))
    (-> 1 seconds) (-> 1 seconds)
    "1s"           (-> 1 seconds)
    "1"            nil
    1              nil
    nil            nil
    "bogus"        nil))

(deftest remotes-config-test
  (testing "Valid configs"
    (are [in out] (= out (extract-and-check-remotes-config in))

      ;; hocon
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s"}]}
      [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]

      ;; ini
      {:server_urls "http://foo.bar:8080", :intervals "120s"}
      [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]


      {:server_urls "http://one:8080,http://two:8080", :intervals "1s,2s"}
      [{:server_url "http://one:8080", :interval (parse-period "1s")}
       {:server_url "http://two:8080", :interval (parse-period "2s")}]

      ;; default port
      {:server_urls "https://foo.bar", :intervals "120s"}
      [{:server_url "https://foo.bar:8081", :interval (parse-period "120s")}]

      {:server_urls "http://foo.bar", :intervals "120s"}
      [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]))

  (testing "Invalid configs"
    (are [sync-config] (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                                 (extract-and-check-remotes-config sync-config))
      ;; mismatched length
      {:server_urls "foo,bar", :intervals "1s"}

      ;; missing fields
      {:server_urls "foo"}
      {:intervals "1s"}

      ;; extra fields
      {:server_urls "foo,bar", :intervals "1s,2s", :extra "field"}
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s"}] :extra "field"}
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s", :extra "field"}]})))
