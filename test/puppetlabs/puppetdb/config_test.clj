(ns puppetlabs.puppetdb.config-test
  (:import [java.security KeyStore])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.config :refer :all]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.time :as pl-time]
            [clj-time.core :as time]
            [puppetlabs.trapperkeeper.testutils.logging :as tu-log]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.testutils :as tu]
            [clojure.string :as str]
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

(deftest sslv3-warn-test
  (testing "output to log"
    (tu-log/with-log-output log-output
      (let [bad-config {:jetty {:ssl-protocols ["SSLv3" "TLSv1" "TLSv1.1" "TLSv1.2"]}}]
        (is (= bad-config
               (default-ssl-protocols bad-config)))
        (is (.contains (last (first @log-output)) "contains SSLv3")))))

  (testing "output to standard out"
    (let [bad-config {:jetty {:ssl-protocols ["SSLv3" "TLSv1" "TLSv1.1" "TLSv1.2"]}}
          out-str (with-out-str
                    (binding [*err* *out*]
                      (default-ssl-protocols bad-config)))]
      (is (.contains out-str "contains SSLv3"))))

  (testing "defaulted-config"
    (tu-log/with-log-output log-output
      (is (= {:jetty {:ssl-protocols ["TLSv1" "TLSv1.1" "TLSv1.2"]}}
             (default-ssl-protocols {})))
      (is (empty? @log-output))
      (is (str/blank?
             (with-out-str
               (binding [*err* *out*]
                 (default-ssl-protocols {}))))))))

;;;;;;;;;;
;; Function-under-test: hook-tk-parse-config-data'
;;
;; Test cases to consider:
;; 1. Missing configuration file
;;    - triggers an java.io.IOException (specifically, java.io.FileNotFoundException)
;; 2. Empty configuration file
;;    - triggers an java.lang.IllegalArgumentException
;; 3. Invalid configuration file info/format
;;    - triggers an org.ini4j.InvalidFileFormatException
;;
;; Fut is given a fake config parser along with an :exc argument to indicate the exception type to be
;; tested for.  Fut is also told to re-throw an Exception via the 'action-on-error-fn' parameter.
;; Expect fut to output error messages to the log and to stderr.
;;
(defn fake-tk-parse-config-function
  [args]
  (when (= "IOE"  (:exc args)) (throw (java.io.IOException. (str (:exc args)))))
  (when (= "FNFE" (:exc args)) (throw (java.io.FileNotFoundException. (str (:exc args)))))
  (when (= "IAE"  (:exc args)) (throw (java.lang.IllegalArgumentException. (str (:exc args)))))
  (when (= "IFFE" (:exc args)) (throw (org.ini4j.InvalidFileFormatException. (str (:exc args))))))

(deftest hook-tk-parse-config-data-test
  (testing "missing config file, verify java.io.FileNotFoundException occurrence"
    (let [exc-msg "FNFException re-toss for hook-tk-test"]
      (is (thrown-with-msg? Exception (re-pattern exc-msg)
                            (hook-tk-parse-config-data' fake-tk-parse-config-function
                                                        #(throw (Exception. exc-msg))
                                                        {:exc "FNFE" :config "hook-tk-test.ini" :help false}))))
    (let [exc-msg "IOException re-toss for hook-tk-test"]
      (is (thrown-with-msg? Exception (re-pattern exc-msg)
                            (hook-tk-parse-config-data' fake-tk-parse-config-function
                                                        #(throw (Exception. exc-msg))
                                                        {:exc "IOE" :config "hook-tk-test.ini" :help false})))))

  (testing "empty config file, verify java.lang.IllegalArgumentException occurrence"
    (let [exc-msg "IAException re-toss for hook-tk-test"]
      (is (thrown-with-msg? Exception (re-pattern exc-msg)
                            (hook-tk-parse-config-data' fake-tk-parse-config-function
                                                        #(throw (Exception. exc-msg))
                                                        {:exc "IAE" :config "hook-tk-test.ini" :help false})))))

  (testing "invalid config, verify org.ini4j.InvalidFileFormatException occurrence"
    (let [exc-msg "IFFException re-toss for hook-tk-test"]
      (is (thrown-with-msg? Exception (re-pattern exc-msg)
                            (hook-tk-parse-config-data' fake-tk-parse-config-function
                                                        #(throw (Exception. exc-msg))
                                                        {:exc "IFFE" :config "hook-tk-test.ini" :help false}))))))
