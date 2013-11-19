(ns com.puppetlabs.puppetdb.test.config
  (:import [java.security KeyStore])
  (:require [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.config :refer :all]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.time :as pl-time]
            [clj-time.core :as time]
            [clojure.java.io :as io]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [fs.core :as fs]))

(deftest commandproc-configuration
  (testing "should use the thread value specified"
    (let [config (configure-commandproc-threads {:command-processing {:threads 37}})]
      (is (= (get-in config [:command-processing :threads]) 37))))

  (let [with-ncores (fn [cores]
                      (with-redefs [kitchensink/num-cpus (constantly cores)]
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
        (is (pl-time/period? gc-interval))
        (is (= (time/minutes 900) gc-interval))))

    (testing "should default to 60 minutes"
      (let [{:keys [gc-interval]} (:database (configure-gc-params {}))]
        (is (pl-time/period? gc-interval))
        (is (= (time/minutes 60) gc-interval)))))

  (testing "node-ttl"
    (testing "should parse node-ttl and return a Pl-Time/Period object"
      (let [{:keys [node-ttl]} (:database (configure-gc-params { :database { :node-ttl "10d" }}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/days 10) (time/days (pl-time/to-days node-ttl))))))
    (testing "should support node-ttl-days for backward compatibility"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params { :database { :node-ttl-days 10 }}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/days 10) node-ttl))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should prefer node-ttl over node-ttl-days"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params { :database {:node-ttl "5d"
                                                                                        :node-ttl-days 10 }}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/days 5) (time/days (pl-time/to-days node-ttl))))
        (is (not (contains? dbconfig :node-ttl-days)))))
    (testing "should default to zero (no expiration)"
      (let [{:keys [node-ttl] :as dbconfig} (:database (configure-gc-params {}))]
        (is (pl-time/period? node-ttl))
        (is (= (time/secs 0) node-ttl)))))

  (testing "report-ttl"
    (testing "should parse report-ttl and produce report-ttl"
      (let [{:keys [report-ttl]} (:database (configure-gc-params { :database { :report-ttl "10d" }}))]
        (is (pl-time/period? report-ttl))
        (is (= (time/days 10) (time/days (pl-time/to-days report-ttl))))))
    (testing "should default to 14 days"
      (let [{:keys [report-ttl]} (:database (configure-gc-params {}))]
        (is (pl-time/period? report-ttl))
        (is (= (time/days 14) (time/days (pl-time/to-days report-ttl))))))))

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

