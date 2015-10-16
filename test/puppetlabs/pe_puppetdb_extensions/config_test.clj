(ns puppetlabs.pe-puppetdb-extensions.config-test
  (:require [clj-time.core :refer [seconds]]
            [clojure.test :refer :all]
            [puppetlabs.pe-puppetdb-extensions.config :refer :all :as s]
            [puppetlabs.puppetdb.time
             :refer [period? periods-equal? parse-period]]
            [slingshot.test :refer :all]))

(defn periods-or-vals-equal? [x y]
  (if (period? x)
    (periods-equal? x y)
    (= x y)))

(deftest coerce-to-period-test
  (are [in out] (periods-or-vals-equal? out (#'s/coerce-to-period in))
    (-> 1 seconds) (-> 1 seconds)
    "1s"           (-> 1 seconds)
    "1"            nil
    1              nil
    nil            nil
    "bogus"        nil))

(deftest remotes-config-test
  (testing "Valid configs"
    (are [in out] (= out (#'s/fixup-remotes in))

      ;; hocon
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s"}]}
      {:remotes
       [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]}

      ;; ini
      {:server_urls "http://foo.bar:8080", :intervals "120s"}
      {:remotes
       [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]}


      {:server_urls "http://one:8080,http://two:8080", :intervals "1s,2s"}
      {:remotes
       [{:server_url "http://one:8080", :interval (parse-period "1s")}
        {:server_url "http://two:8080", :interval (parse-period "2s")}]}

      ;; default port
      {:server_urls "https://foo.bar", :intervals "120s"}
      {:remotes
       [{:server_url "https://foo.bar:8081", :interval (parse-period "120s")}]}

      {:server_urls "http://foo.bar", :intervals "120s"}
      {:remotes
       [{:server_url "http://foo.bar:8080", :interval (parse-period "120s")}]}))

  (testing "Invalid configs"
    (are [sync-config]
      (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                (#'s/fixup-remotes sync-config))

      ;; mismatched length
      {:server_urls "foo,bar", :intervals "1s"}

      ;; missing fields
      {:server_urls "foo"}
      {:intervals "1s"}

      ;; extra fields
      {:server_urls "foo,bar", :intervals "1s,2s", :extra "field"}
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s"}] :extra "field"}
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s", :extra "field"}]})))
