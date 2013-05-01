(ns com.puppetlabs.puppetdb.test.cli.services
  (:require clojure.string
            [com.puppetlabs.puppetdb.version]
            [com.puppetlabs.utils :as utils])
  (:use [com.puppetlabs.puppetdb.cli.services]
        [clojure.test]
        [com.puppetlabs.testutils.logging :only [with-log-output logs-matching]]
        [clj-time.core :only [days hours minutes secs]]
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
               expected))))))

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

(deftest http-configuration
  (testing "should enable need-client-auth"
    (let [config (configure-web-server {:jetty {:client-auth false}})]
      (is (= (get-in config [:jetty :client-auth]) :need)))))

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
