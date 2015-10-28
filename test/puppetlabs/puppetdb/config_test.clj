(ns puppetlabs.puppetdb.config-test
  (:import [java.security KeyStore])
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.config :refer :all :as conf]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.time :as pl-time]
            [clj-time.core :as time]
            [puppetlabs.trapperkeeper.testutils.logging :as tu-log]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.db :refer [sample-db-config]]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]))

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
    (let [cfg {:database {:subname "baz"}}
          req-re #".* must contain an appropriate .* subname setting"]
      (testing "validate-db-settings"
        ;; Valid config
        (is (= cfg (validate-db-settings cfg)))
        ;; Empty config
        (is (thrown+-with-msg? [:type ::conf/cli-error] req-re
                               (validate-db-settings {})))))

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
  (let [config-with (fn [base-config]
                      (-> base-config
                          (update :database merge sample-db-config)
                          (configure-section :database
                                             write-database-config-in
                                             write-database-config-out)))]
    (testing "gc-interval"
      (testing "should use the value specified in minutes"
        (let [gc-interval (get-in (config-with {:database {:gc-interval 900}})
                                  [:database :gc-interval])]
          (is (pl-time/period? gc-interval))
          (is (= 900 (pl-time/to-minutes gc-interval)))))
      (testing "should default to 60 minutes"
        (let [gc-interval (get-in (config-with {:database {}})
                                  [:database :gc-interval])]
          (is (pl-time/period? gc-interval))
          (is (= 60 (pl-time/to-minutes gc-interval))))))

    (testing "node-ttl"
      (testing "should parse node-ttl and return a Pl-Time/Period object"
        (let [node-ttl (get-in (config-with {:database {:node-ttl "10d"}})
                               [:database :node-ttl])]
          (is (pl-time/period? node-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days node-ttl))))))
      (testing "should default to zero (no expiration)"
        (let [node-ttl (get-in (config-with {}) [:database :node-ttl])]
          (is (pl-time/period? node-ttl))
          (is (= 0 (pl-time/to-seconds node-ttl))))))
    (testing "report-ttl"
      (testing "should parse report-ttl and produce report-ttl"
        (let [report-ttl (get-in (config-with {:database {:report-ttl "10d"}})
                                 [:database :report-ttl])]
          (is (pl-time/period? report-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days report-ttl))))))
      (testing "should default to 14 days"
        (let [report-ttl (get-in (config-with {}) [:database :report-ttl])]
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
      (is (.contains out-str "[repl] is now retired"))))
  (testing "[database] config deprecations"
    (doseq [param [:classname :subprotocol]]
      (is (= (format "The [database] %s setting has been retired and will be ignored.\n"
                     (name param))
             (with-out-str
               (binding [*err* *out*]
                 (warn-retirements {:database {param "foo"}}))))))))
