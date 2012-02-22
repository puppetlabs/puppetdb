(ns com.puppetlabs.cmdb.test.command
  (:use [com.puppetlabs.cmdb.command]
        [com.puppetlabs.utils]
        [com.puppetlabs.cmdb.testutils]
        [clojure.test]
        [cheshire.core :as json]
        [slingshot.slingshot :only [try+ throw+]]))

(deftest command-parsing
  (testing "Command parsing"

    (let [command "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}"]
      (testing "should work for strings"
        (let [parsed (parse-command command)]
          ;; :annotations will have a :retries element with a time, which
          ;; is hard to test, so disregard that
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed)))))

      (testing "should work for byte arrays"
        (let [parsed (parse-command (.getBytes command "UTF-8"))]
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed)))))

      (testing "should add to the :retries annotation each parse time it's parsed"
        (let [first-parse (parse-command command)
              first-retries (get-in first-parse [:annotations :retries])
              second-parse (parse-command (json/generate-string first-parse))
              second-retries (get-in second-parse [:annotations :retries])]
          (is (= 1 (count first-retries)))
          (is (= 2 (count second-retries))))))

    (testing "should reject invalid input"
      (is (thrown? AssertionError (parse-command "")))
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

(defn global-count
  "Returns the counter for the given global metric"
  [metric-name]
  (.count (global-metric metric-name)))

(deftest exception-handling-middleware
  (testing "Exception handling middleware"
    (testing "should invoke on-fatal when fatal exception occurs"
      (let [on-fatal       (call-counter)
            on-retry       (call-counter)
            on-msg         (fn [msg]
                             (throw+ (fatality! :foo)))
            processor      (wrap-with-exception-handling on-msg on-retry on-fatal)
            prev-seen      (global-count :seen)
            prev-processed (global-count :processed)
            prev-fatal     (global-count :fatal)
            prev-retried   (global-count :retried)]
        (processor :foobar)
        (is (= 1 (- (global-count :seen) prev-seen)))
        (is (= 1 (times-called on-fatal)))
        (is (= 1 (- (global-count :fatal) prev-fatal)))
        (is (= 0 (- (global-count :processed) prev-processed)))
        (is (= 0 (- (global-count :retried) prev-retried)))
        (is (= 0 (times-called on-retry)))))

    (testing "should invoke on-retry when non-fatal exception occurs"
      (let [on-fatal       (call-counter)
            on-retry       (call-counter)
            on-msg         (fn [msg]
                             (throw (IllegalArgumentException. "foo")))
            processor      (wrap-with-exception-handling on-msg on-retry on-fatal)
            prev-seen      (global-count :seen)
            prev-processed (global-count :processed)
            prev-fatal     (global-count :fatal)
            prev-retried   (global-count :retried)]
        (processor :foobar)
        (is (= 1 (- (global-count :seen) prev-seen)))
        (is (= 0 (- (global-count :processed) prev-processed)))
        (is (= 0 (times-called on-fatal)))
        (is (= 0 (- (global-count :fatal) prev-fatal)))
        (is (= 1 (times-called on-retry)))
        (is (= 1 (- (global-count :retried) prev-retried)))))

    (testing "should invoke on-retry on on exceptions"
      (let [on-fatal     (call-counter)
            on-retry     (call-counter)
            on-msg       (fn [msg]
                           (when (even? msg)
                             (throw (IllegalArgumentException. "foo"))))
            processor    (wrap-with-exception-handling on-msg on-retry on-fatal)
            prev-seen      (global-count :seen)
            prev-processed (global-count :processed)
            prev-fatal     (global-count :fatal)
            prev-retried   (global-count :retried)]
        (doseq [n (range 5)]
          (processor n))
        (is (= 5 (- (global-count :seen) prev-seen)))
        (is (= 2 (- (global-count :processed) prev-processed)))
        (is (= 0 (times-called on-fatal)))
        (is (= 0 (- (global-count :fatal) prev-fatal)))
        ;; Only retry when the number is even, which is 3 times
        (is (= 3 (times-called on-retry)))
        (is (= 3 (- (global-count :retried) prev-retried)))))))

(deftest command-counting-middleware
  (testing "Command counting middleware"
    (testing "should mark the supplied meter and invoke the wrapped function"
      (let [meter (global-metric :seen)
            prev-seen (.count meter)
            called (call-counter)
            counter (wrap-with-meter called meter)]
        (counter "{}")
        (is (= 1 (- (.count meter) prev-seen)))
        (is (= 1 (times-called called)))))))

(deftest command-parsing-middleware
  (testing "Command parsing middleware"

    (testing "should invoke its on-failure handler if a command can't be parsed"
      (let [called (call-counter)
            failed (call-counter)
            parser (wrap-with-command-parser called failed)]
        (parser "/s++-")
        (is (= 0 (times-called called)))
        (is (= 1 (times-called failed)))))

    (testing "should normally pass through a parsed message"
      (let [called (call-counter)
            failed (call-counter)
            parser (wrap-with-command-parser called failed)]
        (parser "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}")
        (is (= 1 (times-called called)))
        (is (= 0 (times-called failed)))))))

(deftest command-processing-middleware
  (testing "Command processing middleware"

    (testing "should work normally if a message has not yet exceeded the max allowable retries"
      (let [called         (call-counter)
            prev-discarded (global-count :discarded)
            processor      (wrap-with-discard called 5)]
        (processor {:command "foobar" :version 1 :retries 5})
        (is (= 1 (times-called called)))
        (is (= 0 (- (global-count :discarded) prev-discarded)))
        ;; Verify that all the command-specific metrics are present
        (is (= (into #{} (keys (get-in @metrics ["foobar" 1])))
               #{:seen :processed :fatal :retried :discarded :processing-time :retry-counts}))))

    (testing "should discard messages that exceed the max allowable retries"
      (let [called    (call-counter)
            prev-discarded (global-count :discarded)
            processor (wrap-with-discard called 5)
            retries (repeatedly 1000 (constantly nil))]
        (processor {:command "foobar" :version 1 :annotations {:retries retries}})
        (is (= 0 (times-called called)))
        (is (= 1 (- (global-count :discarded) prev-discarded)))))))

(deftest thread-name-middleware
  (testing "Thread naming middleware"

    (testing "should use the supplied prefix"
      (let [f (fn [_] (-> (Thread/currentThread)
                          (.getName)
                          (.startsWith "foobar")))
            p (wrap-with-thread-name f "foobar")]
        (is (= true (p :unused)))))

    (testing "should use different names for different threads"
      ;; Create 2 threads, each of which places their thread's name
      ;; into an atom. When the threads complete, the atom should
      ;; contain 2 distinct names, each with the correct prefix.
      (let [names (atom #{})
            f     (fn [_] (swap! names conj (.getName (Thread/currentThread))))
            p     (wrap-with-thread-name f "foobar")
            t1    (Thread. #(p :unused))
            t2    (Thread. #(p :unused))]
        (.start t1)
        (.start t2)
        (.join t1)
        (.join t2)
        (is (= (count @names) 2))
        (is (= true (every? #(.startsWith % "foobar") @names)))))))
