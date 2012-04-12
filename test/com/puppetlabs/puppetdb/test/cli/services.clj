(ns com.puppetlabs.puppetdb.test.cli.services
  (:use [com.puppetlabs.puppetdb.cli.services]
        [clojure.test]))

(deftest commandproc-configuration
  (testing "should use the thread value specified"
    (let [config (configure-commandproc-threads {:command-processing {:threads 37}})]
      (is (= (get-in config [:command-processing :threads]) 37))))

  (testing "should default to half the available CPUs"
    (let [config (configure-commandproc-threads {})
          expected (-> (Runtime/getRuntime)
                     (.availableProcessors)
                     (/ 2)
                     (int))]
      (is (= (get-in config [:command-processing :threads]) expected)))))

(deftest database-configuration
  (testing "database"
    (testing "should use the value specified"
      (let [config (configure-database {:database {:classname "something"}})]
        (is (= (get-in config [:database :classname]) "something"))
        (is (nil? (get-in config [:database :subprotocol])))
        (is (nil? (get-in config [:database :subname])))))

    (testing "should default to hsqldb"
      (let [config (configure-database {})
            expected {:classname "org.hsqldb.jdbcDriver"
                      :subprotocol "hsqldb"
                      :subname "file:/var/lib/puppetdb/db;hsqldb.tx=mvcc;sql.syntax_pgs=true"}]
        (is (= (dissoc (:database config) :gc-interval)
               expected)))))

  (testing "gc-interval"
    (testing "should use the value specified"
      (let [config (configure-database {:database {:gc-interval 900}})]
        (is (= (get-in config [:database :gc-interval]) 900))))

    (testing "should default to 60 minutes"
      (let [config (configure-database {})]
        (is (= (get-in config [:database :gc-interval]) 60))))))

(deftest http-configuration
  (testing "should enable need-client-auth"
    (let [config (configure-web-server {:jetty {:need-client-auth false}})]
      (is (= (get-in config [:jetty :need-client-auth]) true)))))
