(ns puppetlabs.puppetdb.config-test
  (:import [java.security KeyStore])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.config :refer :all]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.time :as pl-time]
            [clj-time.core :as time]
            [puppetlabs.trapperkeeper.testutils.logging :as tu-log]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.string :as str]
            [fs.core :as fs]))

(deftest puppetdb-configuration
  (testing "puppetdb-configuration"
    (let [configure-puppetdb (fn [config] (configure-section config :puppetdb puppetdb-config-in puppetdb-config-out))]
      (testing "should convert disable-update-checking value to boolean, if it is specified"
        (let [config (configure-puppetdb {:puppetdb {:disable-update-checking "true"}})]
          (is (= (get-in config [:puppetdb :disable-update-checking]) true)))
        (let [config (configure-puppetdb {:puppetdb {:disable-update-checking "false"}})]
          (is (= (get-in config [:puppetdb :disable-update-checking]) false)))
        (let [config (configure-puppetdb {:puppetdb {:disable-update-checking "some-string"}})]
          (is (= (get-in config [:puppetdb :disable-update-checking]) false))))

      (testing "should throw exception if disable-update-checking cannot be converted to boolean"
        (is (thrown? clojure.lang.ExceptionInfo
                     (configure-puppetdb {:puppetdb {:disable-update-checking 1337}}))))

      (testing "disable-update-checking should default to 'false' if left unspecified"
        (let [config (configure-puppetdb {})]
          (is (= (get-in config [:puppetdb :disable-update-checking]) false)))))))

(deftest commandproc-configuration
  (let [configure-command-params (fn [config] (configure-section config :command-processing command-processing-in command-processing-out))]
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
        (is (= (with-ncores 1) 1))))))

(deftest database-configuration
  (testing "database"
    (testing "should use the value specified"
      (let [config (defaulted-db-connection {:database {:classname "something"
                                                        :subname "stuff"
                                                        :subprotocol "more stuff"}})]
        (is (= (get-in config [:database :classname]) "something"))
        (is (= "more stuff" (get-in config [:database :subprotocol])))
        (is (= "stuff" (get-in config [:database :subname])))))

    (testing "should default to hsqldb"
      (let [config (defaulted-db-connection {:global {:vardir "/var/lib/puppetdb"}})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (select-keys (:database config) #{:classname :subprotocol :subname})
               expected))))

    (testing "the read-db defaulted to the specified write-db"
      (let [config (-> {:database {:classname "something"
                                   :subname "stuff"
                                   :subprotocol "more stuff"}}
                       (configure-section :database write-database-config-in write-database-config-out)
                       configure-read-db)]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (= "more stuff" (get-in config [:read-database :subprotocol])))
        (is (= "stuff" (get-in config [:read-database :subname])))))

    (testing "the read-db should be specified by a read-database property"
      (let [config (-> {:database {:classname "wrong"
                                   :subname "wronger"
                                   :subprotocol "wrongest"}
                        :read-database {:classname "something"
                                        :subname "stuff"
                                        :subprotocol "more stuff"}}
                       (configure-section :database write-database-config-in write-database-config-out)
                       configure-read-db)]
        (is (= (get-in config [:read-database :classname]) "something"))
        (is (= "more stuff" (get-in config [:read-database :subprotocol])))
        (is (= "stuff" (get-in config [:read-database :subname])))))))

(deftest garbage-collection
  (let [configure-dbs (fn [config]
                        (-> config
                            defaulted-db-connection
                            (configure-section :database write-database-config-in write-database-config-out)))]
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
      (testing "should default to zero (no expiration)"
        (let [{:keys [node-ttl] :as dbconfig} (:database (configure-dbs {}))]
          (is (pl-time/period? node-ttl))
          (is (= 0 (pl-time/to-seconds node-ttl))))))
    (testing "report-ttl"
      (testing "should parse report-ttl and produce report-ttl"
        (let [{:keys [report-ttl]} (:database (configure-dbs { :database { :report-ttl "10d" }}))]
          (is (pl-time/period? report-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days report-ttl))))))
      (testing "should default to 14 days"
        (let [{:keys [report-ttl]} (:database (configure-dbs {}))]
          (is (pl-time/period? report-ttl))
          (is (= (time/days 14) (time/days (pl-time/to-days report-ttl)))))))))

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
      (is (= (str/lower-case product-name)
             (normalize-product-name product-name)))))

  (testing "should disallow anything else"
    (is (thrown-with-msg? IllegalArgumentException #"product-name puppet is illegal"
                          (normalize-product-name "puppet")))))

(deftest warn-retirements-test
  (testing "output to standard out"
    (let [bad-config {:repl {:port 123}}
          out-str (with-out-str
                    (binding [*err* *out*]
                      (warn-retirements bad-config)))]
      (is (.contains out-str "[repl] is now retired")))))
