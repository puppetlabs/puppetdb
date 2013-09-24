(ns com.puppetlabs.puppetdb.test.cli.services
  (:import [java.security KeyStore])
  (:require clojure.string
            [fs.core :refer (absolute-path temp-file)]
            [com.puppetlabs.puppetdb.version]
            [com.puppetlabs.utils :as utils])
  (:use [com.puppetlabs.puppetdb.cli.services]
        [clojure.test]
        [com.puppetlabs.testutils.logging :only [with-log-output logs-matching]]
        [clj-time.core :only [days hours minutes secs]]
        [clojure.java.io :only [resource]]
        [com.puppetlabs.time :only [to-secs to-minutes to-hours to-days period?]]))

(deftest update-checking
  (testing "should check for updates if running as puppetdb"
    (with-redefs [com.puppetlabs.puppetdb.version/update-info (constantly {:version "0.0.0" :newer true})]
      (with-log-output log-output
        (maybe-check-for-updates "puppetdb" "update-server!" {})
        (is (= 1 (count (logs-matching #"Newer version 0.0.0 is available!" @log-output)))))))

  (testing "should skip the update check if running as pe-puppetdb"
    (with-log-output log-output
      (maybe-check-for-updates "pe-puppetdb" "update-server!" {})
      (is (= 1 (count (logs-matching #"Skipping update check on Puppet Enterprise" @log-output)))))))

(deftest commandproc-configuration
  (testing "should use the thread value specified"
    (let [config (configure-commandproc-threads {:command-processing {:threads 37}})]
      (is (= (get-in config [:command-processing :threads]) 37))))

  (let [with-ncores (fn [cores]
                      (with-redefs [utils/num-cpus (constantly cores)]
                        (-> (configure-commandproc-threads {})
                            (get-in [:command-processing :threads]))))]
    (testing "should default to half the available CPUs"
      (is (= (with-ncores 4) 2)))
    (testing "should default to half the available CPUs, rounding down"
      (is (= (with-ncores 5) 2)))
    (testing "should default to half the available CPUs, even on single core boxes"
      (is (= (with-ncores 1) 1)))))

(deftest database-configuration
  (testing "database"
    (testing "should use the value specified"
      (let [config (configure-database {:database {:classname "something"}})]
        (is (= (get-in config [:database :classname]) "something"))
        (is (nil? (get-in config [:database :subprotocol])))
        (is (nil? (get-in config [:database :subname])))))

    (testing "should default to hsqldb"
      (let [config (configure-database {:global {:vardir "/var/lib/puppetdb"}})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (select-keys (:database config) #{:classname :subprotocol :subname})
               expected))))
    
    (testing "the read-db defaulted to the specified write-db"
      (let [config (configure-database {:database {:classname "something"}})]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (nil? (get-in config [:read-database :subprotocol])))
        (is (nil? (get-in config [:read-database :subname])))))
    
    (testing "the read-db defaulted to the hsql write-db-default"
      (let [config (configure-database {:global {:vardir "/var/lib/puppetdb"}})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (select-keys (:read-database config) #{:classname :subprotocol :subname})
               expected))))

    (testing "the read-db should be specified by a read-database property"
      (let [config (configure-database {:read-database {:classname "something"}})]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (nil? (get-in config [:read-database :subprotocol])))
        (is (nil? (get-in config [:read-database :subname])))))))

(deftest garbage-collection
  (testing "gc-interval"
    (testing "should use the value specified in minutes"
      (let [{:keys [gc-interval]} (:database (configure-gc-params {:database {:gc-interval 900}}))]
        (is (period? gc-interval))
        (is (= (minutes 900) gc-interval))))

    (testing "should default to 60 minutes"
      (let [{:keys [gc-interval]} (:database (configure-gc-params {}))]
        (is (period? gc-interval))
        (is (= (minutes 60) gc-interval)))))

  (testing "node-ttl"
    (testing "should parse node-ttl and return a Period object"
      (let [{:keys [node-ttl]} (:database (configure-gc-params { :database { :node-ttl "10d" }}))]
        (is (period? node-ttl))
        (is (= (days 10) (days (to-days node-ttl))))))
    (testing "should support node-ttl-days for backward compatibility"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params { :database { :node-ttl-days 10 }}))]
        (is (period? node-ttl))
        (is (= (days 10) node-ttl))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should prefer node-ttl over node-ttl-days"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params { :database {:node-ttl "5d"
                                                                                        :node-ttl-days 10 }}))]
        (is (period? node-ttl))
        (is (= (days 5) (days (to-days node-ttl))))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should default to zero (no expiration)"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params {}))]
        (is (period? node-ttl))
        (is (= (secs 0) node-ttl)))))

  (testing "report-ttl"
    (testing "should parse report-ttl and produce report-ttl"
      (let [{:keys [report-ttl]} (:database (configure-gc-params { :database { :report-ttl "10d" }}))]
        (is (period? report-ttl))
        (is (= (days 10) (days (to-days report-ttl))))))
    (testing "should default to 14 days"
      (let [{:keys [report-ttl]} (:database (configure-gc-params {}))]
        (is (period? report-ttl))
        (is (= (days 14) (days (to-days report-ttl))))))))

(deftest jetty7-minimum-threads-test
  (testing "should return the same number when higher than num-cpus"
    (is (= 500 (jetty7-minimum-threads 500 1))))
  (testing "should set the number to min threads when it is higher and return a warning"
    (with-log-output logs
      (is (= 4 (jetty7-minimum-threads 1 4)))
      (is (= 1 (count (logs-matching #"max-threads = 1 is less than the minium allowed on this system for Jetty 7 to operate." @logs)))))))

(deftest http-configuration
  (testing "should enable need-client-auth"
    (let [config (configure-web-server {:jetty {:client-auth false}})]
      (is (= (get-in config [:jetty :client-auth]) :need))))
  (let [old-config {:keystore       "/some/path"
                    :key-password   "pw"
                    :truststore     "/some/other/path"
                    :trust-password "otherpw"}]
    (testing "should not muck with keystore/truststore settings if PEM-based SSL
              settings are not provided"
      (let [processed-config (:jetty (configure-web-server {:jetty old-config}))]
        (is (= old-config
               (select-keys processed-config
                 [:keystore :key-password :truststore :trust-password])))))
    (testing "should fail if some but not all of the PEM-based SSL settings are found"
      (let [partial-pem-config (merge old-config {:ssl-ca-cert "/some/path"})]
        (is (thrown-with-msg? java.lang.IllegalArgumentException
              #"If configuring SSL from Puppet PEM files, you must provide all of the following options"
              (configure-web-server {:jetty partial-pem-config})))))

    (let [pem-config (merge old-config
                        {:ssl-key     (resource "com/puppetlabs/test/ssl/private_keys/localhost.pem")
                         :ssl-cert    (resource "com/puppetlabs/test/ssl/certs/localhost.pem")
                         :ssl-ca-cert (resource "com/puppetlabs/test/ssl/certs/ca.pem")})]
      (testing "should warn if both keystore-based and PEM-based SSL settings are found"
        (with-log-output logs
          (configure-web-server {:jetty pem-config})
          (is (= 1 (count (logs-matching #"Found settings for both keystore-based and Puppet PEM-based SSL" @logs))))))
      (testing "should prefer PEM-based SSL settings, override old keystore settings
                  with instances of java.security.KeyStore, and remove PEM settings
                  from final jetty config hash"
        (let [processed-config (:jetty (configure-web-server {:jetty pem-config}))]
          (is (instance? KeyStore (:keystore processed-config)))
          (is (instance? KeyStore (:truststore processed-config)))
          (is (string? (:key-password processed-config)))
          (is (not (contains? processed-config :trust-password)))
          (is (not (contains? processed-config :ssl-key)))
          (is (not (contains? processed-config :ssl-cert)))
          (is (not (contains? processed-config :ssl-ca-cert)))))))
  (testing "should set max-threads"
    (let [config (configure-web-server {:jetty {}})]
      (is (contains? (:jetty config) :max-threads))))
  (testing "should merge configuration with initial-configs correctly"
    (let [user-config {:jetty {:truststore "foo"}}
          config      (configure-web-server user-config)]
      (is (= config {:jetty {:truststore "foo" :max-threads 50 :client-auth :need}})))
    (let [user-config {:jetty {:max-threads 500 :truststore "foo"}}
          config      (configure-web-server user-config)]
      (is (= config {:jetty {:truststore "foo" :max-threads 500 :client-auth :need}})))))

(deftest product-name-validation
  (doseq [product-name ["puppetdb" "pe-puppetdb"]]
    (testing (format "should accept %s and return it" product-name)
      (is (= product-name
             (normalize-product-name product-name)))))

  (doseq [product-name ["PUPPETDB" "PE-PUPPETDB" "PuppetDB" "PE-PuppetDB"]]
    (testing (format "should accept %s and return it lower-cased" product-name)
      (is (= (clojure.string/lower-case product-name)
             (normalize-product-name product-name)))))

  (testing "should disallow anything else"
    (is (thrown-with-msg? IllegalArgumentException #"product-name puppet is illegal"
          (normalize-product-name "puppet")))))

(deftest vardir-validation
  (testing "should fail if it's not specified"
    (is (thrown-with-msg? IllegalArgumentException #"is not specified"
          (validate-vardir nil))))

  (testing "should fail if it's not an absolute path"
    (is (thrown-with-msg? IllegalArgumentException #"must be an absolute path"
          (validate-vardir "foo/bar/baz"))))

  (testing "should fail if it doesn't exist"
    (is (thrown-with-msg? java.io.FileNotFoundException #"does not exist"
          (validate-vardir "/abc/def/ghi"))))

  (testing "should fail if it's not a directory"
    (let [filename (doto (java.io.File/createTempFile "not_a" "directory")
                     (.deleteOnExit))]
      (is (thrown-with-msg? java.io.FileNotFoundException #"is not a directory"
            (validate-vardir filename)))))

  (testing "should fail if it's not writable"
    (let [filename (doto (java.io.File/createTempFile "not" "writable")
                     (.deleteOnExit)
                     (.delete)
                     (.mkdir)
                     (.setReadOnly))]
      (is (thrown-with-msg? java.io.FileNotFoundException #"is not writable"
            (validate-vardir filename)))))

  (testing "should return the value if everything is okay"
    (let [filename (doto (java.io.File/createTempFile "totally" "okay")
                     (.deleteOnExit)
                     (.delete)
                     (.mkdir)
                     (.setWritable true))]
      (is (= (validate-vardir filename) filename)))))

(deftest whitelisting
  (testing "should log on reject"
    (let [wl (temp-file)]
      (.deleteOnExit wl)
      (spit wl "foobar")
      (let [f (build-whitelist-authorizer (absolute-path wl))]
        (is (true? (f {:ssl-client-cn "foobar"})))
        (with-log-output logz
          (is (false? (f {:ssl-client-cn "badguy"})))
          (is (= 1 (count (logs-matching #"^badguy rejected by certificate whitelist " @logz)))))))))
