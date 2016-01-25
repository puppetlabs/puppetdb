(ns puppetlabs.pe-puppetdb-extensions.config-test
  (:require [clj-time.core :refer [seconds]]
            [clojure.test :refer :all]
            [puppetlabs.pe-puppetdb-extensions.config :refer :all :as s]
            [puppetlabs.puppetdb.time :refer [period? periods-equal? parse-period]]
            [slingshot.test :refer :all])
  (:import
   [org.joda.time Period ReadablePeriod PeriodType DateTime]
   [java.net URI]))

(defn periods-or-vals-equal? [x y]
  (if (period? x)
    (periods-equal? x y)
    (= x y)))

(deftest remotes-config-test
  (testing "Valid configs"
    (are [in out] (= out (#'s/parse-sync-config in))

      ;; hocon
      {:remotes [{:server_url "https://foo.bar:8081", :interval "120s"}
                 {:server_url "https://foo.bar:8089" :interval "120s"}]}
      {:remotes
       [{:server-url (URI. "https://foo.bar:8081"), :interval (parse-period "120s")}
        {:server-url (URI. "https://foo.bar:8089"), :interval (parse-period "120s")}]}

      ;; ini
      {:server_urls "https://foo.bar:8081", :intervals "120s"}
      {:remotes
       [{:server-url (URI. "https://foo.bar:8081"), :interval (parse-period "120s")}]}

      {:server_urls "https://one:8081,https://two:8081", :intervals "1s,2s"}
      {:remotes
       [{:server-url (URI. "https://one:8081"), :interval (parse-period "1s")}
        {:server-url (URI. "https://two:8081"), :interval (parse-period "2s")}]}

      ;; default port
      {:server_urls "https://foo.bar", :intervals "120s"}
      {:remotes
       [{:server-url (URI. "https://foo.bar:8081"), :interval (parse-period "120s")}]}

      {:server_urls "https://foo.bar", :intervals "120s"}
      {:remotes
       [{:server-url (URI. "https://foo.bar:8081"), :interval (parse-period "120s")}]}


      ;; http urls
      {:server_urls "http://foo.bar" :intervals "120s", :allow_unsafe_cleartext_sync true}
      {:remotes [{:server-url (URI. "http://foo.bar:8080"), :interval (parse-period "120s")}]
       :allow-unsafe-cleartext-sync true}

      ;; unsafe sync trigger
      {:server_urls "https://foo.bar", :intervals "120s", :allow_unsafe_sync_triggers true}
      {:remotes
       [{:server-url (URI. "https://foo.bar:8081"), :interval (parse-period "120s")}]
       :allow-unsafe-sync-triggers true}))

  (testing "Invalid configs"
    (are [sync-config]
      (thrown+? [:type :puppetlabs.puppetdb.utils/cli-error]
                (#'s/parse-sync-config sync-config))

      ;; mismatched length
      {:server_urls "foo,bar", :intervals "1s"}

      ;; missing fields
      {:server_urls "foo"}
      {:intervals "1s"}

      ;; extra fields
      {:server_urls "foo,bar", :intervals "1s,2s", :extra "field"}
      {:remotes [{:server_url "https://foo.bar:8081", :interval "120s"}] :extra "field"}

      ;; http without opt-in
      {:remotes [{:server_url "http://foo.bar:8080", :interval "120s"}]})))
