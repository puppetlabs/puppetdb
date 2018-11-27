(ns puppetlabs.puppetdb.config-test
  (:import [java.security KeyStore]
           [java.util.regex Pattern])
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

    (testing "should allow for `pe-puppetdb`'s historical-catalogs-limit setting"
      (let [config (configure-puppetdb {})]
        (is (= (get-in config [:puppetdb :historical-catalogs-limit]) 0)))
      (let [config (configure-puppetdb {:puppetdb {:historical-catalogs-limit 5}})]
        (is (= (get-in config [:puppetdb :historical-catalogs-limit]) 5))))

    (testing "disable-update-checking should default to 'false' if left unspecified"
      (let [config (configure-puppetdb {})]
        (is (= (get-in config [:puppetdb :disable-update-checking]) false))))))

(deftest commandproc-configuration
  (testing "should use the thread value specified"
    (let [config (configure-command-processing {:command-processing {:threads 37}})]
      (is (= (get-in config [:command-processing :threads]) 37))))

  (testing "retired command processing config"
    (doseq [cmd-proc-key [:store-usage :temp-usage :memory-usage :max-frame-size]]
      (let [cmd-proc-config {:command-processing {cmd-proc-key 10000}}
            out-str (with-out-str
                      (binding [*err* *out*]
                        (configure-command-processing cmd-proc-config)))]
        (is (.contains out-str
                       (format "The configuration item `%s`" (name cmd-proc-key)))))))

  (let [with-ncores (fn [cores]
                      (with-redefs [kitchensink/num-cpus (constantly cores)]
                        (half-the-cores*)))]
    (testing "should default to half the available CPUs"
      (is (= (with-ncores 4) 2)))
    (testing "should default to half the available CPUs, rounding down"
      (is (= (with-ncores 5) 2)))
    (testing "should default to half the available CPUs, even on single core boxes"
      (is (= (with-ncores 1) 1)))))

(deftest blacklist-regex-validates-and-returns-patterns
  (is (every? #(instance? Pattern %)
              (-> {:database {:subname "bar"
                              :facts-blacklist-type "regex"
                              :facts-blacklist ["^foo$" "bar.*" "b[a].?z"]}}
                  convert-blacklist-config
                  (get-in [:database :facts-blacklist]))))

  (is (thrown+-with-msg? [:type ::conf/cli-error]
                         #".*Unclosed character class near index 4\.*"
                         (-> {:database {:subname "bar"
                                         :facts-blacklist-type "regex"
                                         :facts-blacklist ["^foo[" "(bar.*"]}}
                             convert-blacklist-config))))

(deftest blacklist-conversion-is-no-op-when-type-literal
  ;; with blacklist-type set to literal all blacklist entries kept as literal strings
  (is (= ["^foo$" "bar.*" "b[a].?z"]
         (-> {:database {:subname "bar"
                         :facts-blacklist-type "literal"
                         :facts-blacklist ["^foo$" "bar.*" "b[a].?z"]}}
             convert-blacklist-config
             (get-in [:database :facts-blacklist])))))

(deftest blacklist-type-only-accepts-literal-regex-as-values
  (let [config-db (fn [bl-type]
                    (-> {:database {:classname "something"
                                    :subname "stuff"
                                    :subprotocol "more stuff"
                                    :facts-blacklist-type bl-type}}
                        (configure-section :database
                                           write-database-config-in
                                           write-database-config-out)))]

    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Value does not match schema"
                          (config-db "foo")))

    (is (= "regex" (get-in (config-db "regex")
                           [:database :facts-blacklist-type])))

    (is (= "literal" (get-in (config-db "literal")
                             [:database :facts-blacklist-type])))))

(deftest blacklist-type-defaults-to-literal
  (let [config (-> {:database {:classname "something"
                               :subname "stuff"
                               :subprotocol "more stuff"}}
                   (configure-section :database write-database-config-in write-database-config-out)
                   configure-read-db)]
    (is (= (get-in config [:database :facts-blacklist-type]) "literal"))))

(deftest blacklist-converted-correctly-with-ini-and-conf-files
  (let [build-config (fn [x] (-> {:database {:classname "something"
                                             :subname "stuff"
                                             :subprotocol "more stuff"
                                             :facts-blacklist x}}
                                 (configure-section :database write-database-config-in write-database-config-out)
                                 configure-read-db))
        ini-config (build-config "fact1, fact2, fact3")
        hocon-config (build-config ["fact1" "fact2" "fact3"])]
    (is (= (get-in ini-config [:database :facts-blacklist]) ["fact1" "fact2" "fact3"]))
    (is (= (get-in hocon-config [:database :facts-blacklist]) ["fact1" "fact2" "fact3"]))))

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
        (is (= "stuff" (get-in config [:read-database :subname])))))

    (testing "max-pool-size defaults to 25"
      (let [config (-> {:database {:classname "something"
                                   :subname "stuff"
                                   :subprotocol "more stuff"}}
                       (configure-section :database write-database-config-in write-database-config-out)
                       configure-read-db)]
        (is (= (get-in config [:read-database :maximum-pool-size]) 25))
        (is (= (get-in config [:database :maximum-pool-size]) 25))))))

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
      (testing "should default to 7 days"
        (let [node-ttl (get-in (config-with {}) [:database :node-ttl])]
          (is (pl-time/period? node-ttl))
          (is (= 7 (pl-time/to-days node-ttl))))))

    (testing "node-purge-ttl"
      (testing "should parse node-purge-ttl and return a Pl-Time/Period object"
        (let [node-purge-ttl (get-in (config-with {:database {:node-purge-ttl "10d"}})
                               [:database :node-purge-ttl])]
          (is (pl-time/period? node-purge-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days node-purge-ttl))))))
      (testing "should default to 14 days"
        (let [node-purge-ttl (get-in (config-with {}) [:database :node-purge-ttl])]
          (is (pl-time/period? node-purge-ttl))
          (is (= 14 (pl-time/to-days node-purge-ttl))))))

    (testing "node-purge-gc-batch-limit"
      (testing "should use the value specified"
        (let [x (get-in (config-with {:database {:node-purge-gc-batch-limit 3}})
                        [:database :node-purge-gc-batch-limit])]
          (is (integer? x))
          (is (= 3 x))))
      (testing "should default to 25"
        (let [x (get-in (config-with {})
                        [:database :node-purge-gc-batch-limit])]
          (is (integer? x))
          (is (= 25 x)))))

    (testing "report-ttl"
      (testing "should parse report-ttl and produce report-ttl"
        (let [report-ttl (get-in (config-with {:database {:report-ttl "10d"}})
                                 [:database :report-ttl])]
          (is (pl-time/period? report-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days report-ttl))))))
      (testing "should default to 14 days"
        (let [report-ttl (get-in (config-with {}) [:database :report-ttl])]
          (is (pl-time/period? report-ttl))
          (is (= (time/days 14) (time/days (pl-time/to-days report-ttl)))))))

    (testing "resource-events-ttl"
      (testing "should parse resource-events-ttl and produce resource-events-ttl"
        (let [resource-events-ttl (get-in (config-with {:database {:resource-events-ttl "10d"}})
                                          [:database :resource-events-ttl])]
          (is (pl-time/period? resource-events-ttl))
          (is (= (time/days 10) (time/days (pl-time/to-days resource-events-ttl))))))
      (testing "should default to 14 days"
        (let [resource-events-ttl (get-in (config-with {}) [:database :resource-events-ttl])]
          (is (pl-time/period? resource-events-ttl))
          (is (= (time/days 14) (time/days (pl-time/to-days resource-events-ttl)))))))))

(defn vardir [path]
  {:global {:vardir (str path)}})

(deftest vardir-validation
  (testing "should fail if it's not specified"
    (is (thrown-with-msg? IllegalArgumentException #"is not specified"
                          (validate-vardir {:global {:vardir nil}}))))

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
             (vardir filename)))))

  (testing "should support relative paths"
    (let [filename "./target/totally/okay"]
      (doto (fs/file filename)
        (.deleteOnExit)
        (.mkdirs)
        (.setWritable true))
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

(deftest test-default-max-command-size
  (testing "default is disabled"
    (let [default-config (:command-processing (configure-command-processing {}))]
      (is (false? (:reject-large-commands default-config)))))

  (testing "enabled but with default size"
    (let [default-config (:command-processing (configure-command-processing {:command-processing {:reject-large-commands "true"}}))]
      (is (true? (:reject-large-commands default-config)))
      (is (= (default-max-command-size)
             (:max-command-size default-config)))))

  (testing "enabled with included max size"
    (let [default-config (:command-processing (configure-command-processing {:command-processing {:reject-large-commands "true"
                                                                                                  :max-command-size 25000}}))]
      (is (true? (:reject-large-commands default-config)))
      (is (= 25000
             (:max-command-size default-config))))))
