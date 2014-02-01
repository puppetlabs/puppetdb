(ns com.puppetlabs.puppetdb.test.config
  (:import [java.security KeyStore])
  (:require [clojure.test :refer :all]
           [com.puppetlabs.puppetdb.config :refer :all]
           [puppetlabs.kitchensink.core :as kitchensink]
           [com.puppetlabs.time :as pl-time]
           [clj-time.core :as time]
           [com.puppetlabs.testutils.logging :as tu-log]
           [clojure.java.io :as io]
           [com.puppetlabs.puppetdb.testutils :as tu]
           [fs.core :as fs]))

(deftest commandproc-configuration
  (testing "should throw an error on unrecognized config options"
    (is (thrown? clojure.lang.ExceptionInfo (configure-command-params {:command-processing {:foo "foo"}}))))

  (testing "should use the thread value specified"
    (let [config (configure-command-params {:command-processing {:threads 37}})]
      (is (= (get-in config [:command-processing :threads]) 37))))

  (testing "should use the store-usage specified"
    (let [config (configure-command-params {:command-processing {:store-usage 10000}})]
      (is (= (get-in config [:command-processing :store-usage]) 10000))))

  (testing "should use the temp-usage specified"
    (let [config (configure-command-params {:command-processing {:temp-usage 10000}})]
      (is (= (get-in config [:command-processing :temp-usage]) 10000))))

  (let [with-ncores (fn [cores]
                      (with-redefs [kitchensink/num-cpus (constantly cores)]
                        (half-the-cores*)))]
    (testing "should default to half the available CPUs"
      (is (= (with-ncores 4) 2)))
    (testing "should default to half the available CPUs, rounding down"
      (is (= (with-ncores 5) 2)))
    (testing "should default to half the available CPUs, even on single core boxes"
      (is (= (with-ncores 1) 1)))))

(deftest database-configuration
  (testing "database"
    (testing "should throw an error on unrecognized config options"
      (is (thrown? clojure.lang.ExceptionInfo (configure-dbs {:database {:foo "foo"}}))))

    (testing "should use the value specified"
      (let [config (configure-dbs {:database {:classname "something"
                                              :subname "stuff"
                                              :subprotocol "more stuff"}})]
        (is (= (get-in config [:database :classname]) "something"))
        (is (= "more stuff" (get-in config [:database :subprotocol])))
        (is (= "stuff" (get-in config [:database :subname])))))

    (testing "should default to hsqldb"
      (let [config (configure-dbs {:global {:vardir "/var/lib/puppetdb"}})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (select-keys (:database config) #{:classname :subprotocol :subname})
               expected))))

    (testing "the read-db defaulted to the specified write-db"
      (let [config (configure-dbs {:database {:classname "something"
                                              :subname "stuff"
                                              :subprotocol "more stuff"}})]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (= "more stuff" (get-in config [:database :subprotocol])))
        (is (= "stuff" (get-in config [:database :subname])))))

    (testing "the read-db defaulted to the hsql write-db-default"
      (let [config (configure-dbs {:global {:vardir "/var/lib/puppetdb"}})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (select-keys (:read-database config) #{:classname :subprotocol :subname})
               expected))))

    (testing "the read-db should be specified by a read-database property"
      (let [config (configure-dbs {:read-database {:classname "something"
                                                   :subname "stuff"
                                                   :subprotocol "more stuff"}})]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (= "more stuff" (get-in config [:read-database :subprotocol])))
        (is (= "stuff" (get-in config [:read-database :subname])))))))

(deftest garbage-collection
  (testing "gc-interval"
    (testing "should use the value specified in minutes"
      (let [{:keys [gc-interval]} (:database (configure-dbs {:database {:gc-interval 900}}))]
        (is (pl-time/period? gc-interval))
        (is (= 900 (pl-time/to-minutes gc-interval)))))
    (testing "should default to 60 minutes"
      (let [{:keys [gc-interval]} (:database (configure-dbs {:database {}}))]
        (is (pl-time/period? gc-interval))
        (is (= 60 (pl-time/to-minutes gc-interval))))))

  (testing "node-ttl"
    (testing "should parse node-ttl and return a Pl-Time/Period object"
      (let [{:keys [node-ttl]} (:database (configure-dbs { :database { :node-ttl "10d" }}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/days 10) (time/days (pl-time/to-days node-ttl))))))
    (testing "should support node-ttl-days for backward compatibility"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-dbs { :database { :node-ttl-days 10 }}))]
        (is (pl-time/period? node-ttl))
        (is (= 10 (pl-time/to-days node-ttl)))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should prefer node-ttl over node-ttl-days"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-dbs { :database {:node-ttl "5d"
                                                                                   :node-ttl-days 10 }}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/days 5) (time/days (pl-time/to-days node-ttl))))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should default to zero (no expiration)"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-dbs {}))]
        (is (pl-time/period? node-ttl))
        (is (= 0 (pl-time/to-secs node-ttl))))))
  (testing "report-ttl"
    (testing "should parse report-ttl and produce report-ttl"
      (let [{:keys [report-ttl]} (:database (configure-dbs { :database { :report-ttl "10d" }}))]
        (is (pl-time/period? report-ttl))
        (is (= (time/days 10) (time/days (pl-time/to-days report-ttl))))))
    (testing "should default to 14 days"
      (let [{:keys [report-ttl]} (:database (configure-dbs {}))]
        (is (pl-time/period? report-ttl))
        (is (= (time/days 14) (time/days (pl-time/to-days report-ttl))))))))

(deftest jetty7-minimum-threads-test
  (testing "should return the same number when higher than num-cpus"
    (is (= 500 (jetty7-minimum-threads 500 1))))
  (testing "should set the number to min threads when it is higher and return a warning"
    (tu-log/with-log-output logs
      (is (= 4 (jetty7-minimum-threads 1 4)))
      (is (= 1 (count (tu-log/logs-matching #"max-threads = 1 is less than the minium allowed on this system for Jetty 7 to operate." @logs)))))))

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
                        {:ssl-key     (io/resource "com/puppetlabs/test/ssl/private_keys/localhost.pem")
                         :ssl-cert    (io/resource "com/puppetlabs/test/ssl/certs/localhost.pem")
                         :ssl-ca-cert (io/resource "com/puppetlabs/test/ssl/certs/ca.pem")})]
      (testing "should warn if both keystore-based and PEM-based SSL settings are found"
        (tu-log/with-log-output logs
          (configure-web-server {:jetty pem-config})
          (is (= 1 (count (tu-log/logs-matching #"Found settings for both keystore-based and Puppet PEM-based SSL" @logs))))))
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

(defn vardir [path]
  {:global {:vardir (str path)}})

(deftest vardir-validation
  (testing "should fail if it's not specified"
    (is (thrown-with-msg? IllegalArgumentException #"is not specified"
                          (validate-vardir {:global {:vardir nil}}))))

  (testing "should fail if it's not an absolute path"
    (is (thrown-with-msg? IllegalArgumentException #"must be an absolute path"
                          (validate-vardir (vardir "foo/bar/baz")))))

  (testing "should fail if it doesn't exist"
    (is (thrown-with-msg? java.io.FileNotFoundException #"does not exist"
                          (validate-vardir (vardir "/abc/def/ghi")))))

  (testing "should fail if it's not a directory"
    (let [filename (doto (java.io.File/createTempFile "not_a" "directory")
                     (.deleteOnExit))]
      (is (thrown-with-msg? java.io.FileNotFoundException #"is not a directory"
                            (validate-vardir (vardir filename))))))

  (testing "should fail if it's not writable"
    (let [filename (doto (java.io.File/createTempFile "not" "writable")
                     (.deleteOnExit)
                     (.delete)
                     (.mkdir)
                     (.setReadOnly))]
      (is (thrown-with-msg? java.io.FileNotFoundException #"is not writable"
                            (validate-vardir (vardir filename))))))

  (testing "should return the value if everything is okay"
    (let [filename (doto (java.io.File/createTempFile "totally" "okay")
                     (.deleteOnExit)
                     (.delete)
                     (.mkdir)
                     (.setWritable true))]
      (is (= (validate-vardir (vardir filename))
             (vardir filename))))))

(deftest catalog-debugging
  (testing "no changes when debugging is not enabled"
    (is (= {} (configure-catalog-debugging {})))
    (is (= {:global {:catalog-hash-conflict-debugging "false"}}
           (configure-catalog-debugging {:global {:catalog-hash-conflict-debugging "false"}})))
    (is (= {:global {:catalog-hash-conflict-debugging "something that is not true"}}
           (configure-catalog-debugging {:global {:catalog-hash-conflict-debugging "something that is not true"}}))))

  (testing "creating the directory when not present"
    (let [vardir (str (tu/temp-dir))
          config {:global {:vardir vardir
                           :catalog-hash-conflict-debugging "true"}}]
      (is (false? (fs/exists? (catalog-debug-path config))))
      (is (= (assoc-in config [:global :catalog-hash-debug-dir] (str vardir "/debug/catalog-hashes"))
             (configure-catalog-debugging config)))))

  (testing "failure to create directory"
    (let [vardir (str (tu/temp-dir))
          config {:global {:vardir vardir
                           :catalog-hash-conflict-debugging "true"}}
          mkdirs-called? (atom true)]

      (with-redefs [fs/mkdirs (fn [& args]
                                (reset! mkdirs-called? true)
                                (throw (SecurityException. "Stuff is broken")))]
        (is (= config
               (configure-catalog-debugging config))))
      (is (true? @mkdirs-called?)))))

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

