(ns com.puppetlabs.cmdb.test.command
  (:import (com.puppetlabs.cmdb.command command-fatal-exception))
  (:use [com.puppetlabs.cmdb.command]
        [com.puppetlabs.utils]
        [clojure.test]
        [slingshot.core :only [try+ throw+]]))

(deftest command-parsing
  (testing "Command parsing"

    (testing "should work for strings"
      (is (= (parse-command "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}")
             {:command "foo" :version 2 :payload "meh"})))

    (testing "should work for byte arrays"
      (is (= (parse-command (.getBytes "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}" "UTF-8"))
             {:command "foo" :version 2 :payload "meh"})))

    (testing "should reject invalid input"
      (is (thrown? java.io.EOFException (parse-command "")))
      (is (thrown? AssertionError (parse-command "{}")))

      ;; Missing required attributes
      (is (thrown? AssertionError (parse-command "{\"version\": 2, \"payload\": \"meh\"}")))
      (is (thrown? AssertionError (parse-command "{\"version\": 2}")))

      ;; Non-numeric version
      (is (thrown? AssertionError (parse-command "{\"version\": \"2\", \"payload\": \"meh\"}")))

      ;; Non-string command
      (is (thrown? AssertionError (parse-command "{\"command\": 123, \"version\": 2, \"payload\": \"meh\"}")))

      ;; Non-JSON payload
      (is (thrown? Exception (parse-command "{\"command\": \"foo\", \"version\": 2, \"payload\": #{}")))

      ;; Non-UTF-8 byte array
      (is (thrown? Exception (parse-command (.getBytes "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}" "UTF-16")))))))

(defn run-through-command-map [msg-seq on-message]
  "Helper func that will walk through the command-map! event loop and
  track all callbacks, returning the tracking information"
  (let [acked     (transient [])
        retried   (transient [])
        failed    (transient [])
        ack-msg   #(conj! acked %)
        on-retry  (fn [msg e]
                    (conj! retried msg)
                    msg)
        on-fatal  (fn [msg e]
                    (conj! failed msg)
                    msg)]
    (command-map! msg-seq ack-msg on-message on-retry on-fatal)
    [(persistent! acked)
     (persistent! retried)
     (persistent! failed)]))

(deftest command-mapping
  (testing "Command mapping"

    (testing "should ack messages without errors"
      (let [msg-seq [1 2 3]
            on-msg (fn [msg])
            [acked retried failed] (run-through-command-map msg-seq on-msg)]
        (is (= acked [1 2 3]))
        (is (= retried []))
        (is (= failed []))))

    (testing "should retry messages that cause exceptions"
      (let [msg-seq [1 2 3]
            on-msg (fn [msg]
                     (when (even? msg)
                       (throw (RuntimeException. "even number"))))
            [acked retried failed] (run-through-command-map msg-seq on-msg)]
        ;; Despite the error thrown on '2', that message should still be acked
        (is (= acked [1 2 3]))
        ;; Here's the difference: the error on '2' causes it to get retried
        (is (= retried [2]))
        ;; This is a soft-failure, nothing is fatal
        (is (= failed []))))

    (testing "should discard messages that cause fatal errors"
      (let [msg-seq [1 2 3]
            on-msg (fn [msg]
                     (when (even? msg)
                       (throw+ (command-fatal-exception. (RuntimeException. "even number")))))
            [acked retried failed] (run-through-command-map msg-seq on-msg)]
        ;; Despite the error thrown on '2', that message should still be acked
        (is (= acked [1 2 3]))
        ;; Since the error is fatal, nothing is retried
        (is (= retried []))
        (is (= failed [2]))))))

(deftest msg-handling
  (testing "Initial command processing"

    (testing "should throw fatal exceptions if a command can't be parsed"
      (with-redefs [parse-command (fn [msg] (throw (RuntimeException. "parse error")))]
        (let [f (make-msg-handler 0 nil)]
          (is (thrown+? command-fatal-exception (f "{}"))))))

    (testing "should work normally if a message has not yet exceeded the max allowable retries"
      (with-redefs [parse-command (fn [msg] {})
                    process-command! (fn [msg opts] :sentinel)]
        (let [f (make-msg-handler 1 nil)]
          (is (= :sentinel (f :unused))))))

    (testing "should do nothing if a message has exceeded the max allowable retries"
      (with-redefs [parse-command (fn [msg] {:retries 100})
                    process-command! (fn [msg opts]
                                       (throw (RuntimeException.)))]
        (let [f (make-msg-handler 1 nil)]
          (is (= nil (f :unused))))))))
