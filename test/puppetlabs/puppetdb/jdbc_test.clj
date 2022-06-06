(ns puppetlabs.puppetdb.jdbc-test
  (:require [puppetlabs.puppetdb.jdbc :as subject]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.db
             :refer [*db* antonym-data
                     insert-entries
                     defaulted-read-db-config
                     defaulted-write-db-config
                     call-with-antonym-test-database]]))

(defmacro deftest-antonyms [name & body]
  `(deftest ~name
     (call-with-antonym-test-database (fn [] ~@body))))

(defn full-sql-exception-msg [ex]
  (apply str (take-while identity (iterate #(.getNextException %) ex))))

(deftest pool-construction
  (testing "can construct pool with numeric usernames and passwords"
    (call-with-antonym-test-database
     (fn []
       (let [pool (-> *db*
                      (assoc :username "1234" :password "1234")
                      defaulted-write-db-config
                      subject/pooled-datasource)]
         (.close (:datasource pool))))))

  (testing "writes not allowed on read-only pools"
    (call-with-antonym-test-database
     (fn []
       (let [write-pool (-> *db*
                            defaulted-write-db-config
                            subject/pooled-datasource)]
         (with-open [_ (:datasource write-pool)]
           (subject/with-transacted-connection write-pool
             (insert-entries {"foo" 1})
             (is (= [{:value "1"}]
                    (subject/query-to-vec
                     "SELECT value FROM test WHERE key='foo'"))))))
       (let [read-pool (-> (assoc *db* :read-only? true)
                           defaulted-read-db-config
                           subject/pooled-datasource)]
         (with-open [_ (:datasource read-pool)]
           (subject/with-transacted-connection read-pool
             (let [msg (try
                         (insert-entries {"bar" 1})
                         ""
                         (catch java.sql.SQLException ex
                           (full-sql-exception-msg ex)))]
               (is (re-find #"read-only.*transaction" msg)))))))))

  (testing "max connections setting takes effect"
    (call-with-antonym-test-database
      (fn []
        (with-open [data-source (-> *db*
                                    defaulted-read-db-config
                                    (assoc :maximum-pool-size 5)
                                    subject/pooled-datasource
                                    :datasource)]
          (is (= 5 (.getMaximumPoolSize data-source))))))))

(deftest-antonyms query-to-vec
  (testing "query string only"
    (is (= (set (subject/query-to-vec "SELECT key FROM test WHERE key LIKE 'ab%'"))
           (set (map #(hash-map :key (name %)) [:absence :abundant])))))
  (testing "query with params"
    (doseq [[key value] antonym-data]
      (let [query  ["SELECT key, value FROM test WHERE key = ?" key]
            result [{:key key :value value}]]
        (is (= (subject/query-to-vec query) result)
            (str query " => " result " with vector"))
        (is (= (apply subject/query-to-vec query) result)
            (str query " => " result " with multiple params"))))))

(deftest-antonyms limited-query-to-vec
  (testing "query does not exceed limit"
    (is (= (set (subject/limited-query-to-vec 100 "SELECT key FROM test WHERE key LIKE 'ab%'"))
           (set (map #(hash-map :key (name %)) [:absence :abundant])))))
  (testing "query exceeds limit"
    (is (thrown-with-msg? IllegalStateException #"more than the maximum number of results"
                          (subject/limited-query-to-vec 1 "SELECT key FROM test WHERE key LIKE 'ab%'")))))

(deftest-antonyms in-clause
  (testing "single item in collection"
    (is (= "in (?)" (subject/in-clause ["foo"]))))
  (testing "many items in a collection"
    (is (= "in (?,?,?,?,?)" (subject/in-clause (repeat 5 "foo")))))
  (testing "fails on empty collection (not valid SQL)"
    (is (thrown-with-msg? java.lang.AssertionError #"Assert failed"
                          (subject/in-clause []))))
  (testing "fails on nil (not valid SQL)"
    (is (thrown-with-msg? java.lang.AssertionError #"Assert failed"
                          (subject/in-clause nil)))))

(deftest-antonyms in-clause-multi
  (testing "single item in collection, 4 wide"
    (is (= "in ((?,?,?,?))" (subject/in-clause-multi ["foo"] 4))))
  (testing "many items in a collection, 2 wide"
    (is (= "in ((?,?),(?,?),(?,?),(?,?),(?,?))" (subject/in-clause-multi (repeat 5 "foo") 2))))
  (testing "fails on empty collection (not valid SQL)"
    (is (thrown-with-msg? java.lang.AssertionError #"Assert failed"
                          (subject/in-clause-multi [] 1))))
  (testing "fails on nil (not valid SQL)"
    (is (thrown-with-msg? java.lang.AssertionError #"Assert failed"
                          (subject/in-clause-multi nil 1)))))

(deftest-antonyms transaction-isolation
  (let [evaled-body? (atom false)]
    (subject/with-transacted-connection' *db* nil
      (let [conn (:connection (subject/db))]
        (is (false? (.getAutoCommit conn)))
        (is (= java.sql.Connection/TRANSACTION_READ_COMMITTED
               (.getTransactionIsolation conn)))
        (reset! evaled-body? true)))

    (is (true? @evaled-body?))

    (are [isolation-level isolation-level-kwd]
      (subject/with-transacted-connection' *db* isolation-level-kwd
        (let [conn (:connection (subject/db))]
          (= isolation-level (.getTransactionIsolation conn))))
      java.sql.Connection/TRANSACTION_REPEATABLE_READ :repeatable-read
      java.sql.Connection/TRANSACTION_SERIALIZABLE :serializable
      java.sql.Connection/TRANSACTION_READ_COMMITTED :read-committed)))
