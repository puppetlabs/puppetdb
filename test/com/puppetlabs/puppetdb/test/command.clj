(ns com.puppetlabs.puppetdb.test.command
  (:require [fs.core :as fs]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.catalogs :as catalog]
            [com.puppetlabs.puppetdb.examples.reports :as report-examples]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger]])
  (:use [com.puppetlabs.puppetdb.command]
        [com.puppetlabs.puppetdb.testutils]
        [com.puppetlabs.puppetdb.fixtures]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.puppetdb.examples]
        [com.puppetlabs.puppetdb.testutils.reports :only [munge-example-report-for-storage]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]]
        [clj-time.coerce :only [to-timestamp]]
        [clj-time.core :only [days ago]]
        [clojure.test]
        [clojure.tools.logging :only [*logger-factory*]]
        [slingshot.slingshot :only [try+ throw+]]))

(use-fixtures :each with-test-db)

(deftest command-assembly
  (testing "Formatting commands for submission"
    (is (= (assemble-command "my command" 1 [1 2 3 4 5])
           {:command "my command"
            :version 1
            :payload [1 2 3 4 5]}))))

(deftest command-parsing
  (testing "Command parsing"

    (let [command "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}"]
      (testing "should work for strings"
        (let [parsed (parse-command command)]
          ;; :annotations will have a :attempts element with a time, which
          ;; is hard to test, so disregard that
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed)))))

      (testing "should work for byte arrays"
        (let [parsed (parse-command (.getBytes command "UTF-8"))]
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed))))))

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
                             (throw+ (fatality :foo)))
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

    (testing "should work normally if a message has not yet exceeded the max allowable attempts"
      (let [called         (call-counter)
            on-discard     (call-counter)
            prev-discarded (global-count :discarded)
            processor      (wrap-with-discard called on-discard 5)]
        (processor {:command "foobar" :version 1 :attempts [{} {} {}]})
        (is (= 1 (times-called called)))
        (is (= 0 (times-called on-discard)))
        (is (= 0 (- (global-count :discarded) prev-discarded)))
        ;; Verify that all the command-specific metrics are present
        (is (= (set (keys (get-in @metrics ["foobar" 1])))
               #{:seen :processed :fatal :retried :discarded :processing-time :retry-counts}))))

    (testing "should discard messages that exceed the max allowable attempts"
      (let [called         (call-counter)
            on-discard     (call-counter)
            prev-discarded (global-count :discarded)
            processor      (wrap-with-discard called on-discard 5)
            attempts       [{} {} {} {} {}]]
        (processor {:command "foobar" :version 1 :annotations {:attempts attempts}})
        (is (= 0 (times-called called)))
        (is (= 1 (times-called on-discard)))
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

(defmacro test-msg-handler*
  [command publish-var discard-var opts-map & body]
  `(let [log-output#     (atom [])
         publish#        (call-counter)
         discard-dir#    (fs/temp-dir)
         handle-message# (produce-message-handler publish# discard-dir# ~opts-map)
         msg#            (json/generate-string ~command)]
     (try
       (binding [*logger-factory* (atom-logger log-output#)]
         (handle-message# msg#))
       (let [~publish-var publish#
             ~discard-var discard-dir#]
         ~@body
         ; Uncommenting this line can be very useful for debugging
;         (println @log-output#)
         )
       (finally
         (fs/delete-dir discard-dir#)))))

(defmacro test-msg-handler
  "Runs `command` (after converting to JSON) through the MQ message handlers.
   `body` is executed with `publish-var` bound to the number of times the message
   was processed and `discard-var` bound to the directory that contains failed messages."
  [command publish-var discard-var & body]
  `(test-msg-handler* ~command ~publish-var ~discard-var {:db *db*} ~@body))

(defmacro test-msg-handler-with-opts
  "Similar to test-msg-handler, but allows the passing of additional config
   options to the message handler via `opts-map`."
  [command publish-var discard-var opts-map & body]
  `(test-msg-handler* ~command ~publish-var ~discard-var (merge {:db *db*}
                                                                ~opts-map)
                      ~@body))

(deftest command-processor-integration
  (let [command {:command "some command" :version 1 :payload "payload"}]
    (testing "correctly formed messages"

      (testing "which are not yet expired"

        (testing "when successful should not raise errors or retry"
          (with-redefs [process-command! (constantly true)]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "when a fatal error occurs should be discarded to the dead letter queue"
          (with-redefs [process-command! (fn [cmd opt] (throw+ (fatality (Exception. "fatal error"))))]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (= 1 (count (fs/list-dir discard-dir)))))))

        (testing "when a non-fatal error occurs should be requeued with the error recorded"
          (with-redefs [process-command! (fn [cmd opt] (throw+ (Exception. "non-fatal error")))]
            (test-msg-handler command publish discard-dir
              (is (empty? (fs/list-dir discard-dir)))
              (let [[msg & _] (first (args-supplied publish))
                    published (parse-command msg)
                    attempt   (first (get-in published [:annotations :attempts]))]
                (is (re-find #"java.lang.Exception: non-fatal error" (:error attempt)))
                (is (:trace attempt)))))))

      (testing "should be discarded if expired"
        (let [command (assoc-in command [:annotations :attempts] (repeat maximum-allowable-retries {}))
              process-counter (call-counter)]
          (with-redefs [process-command! process-counter]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (= 1 (count (fs/list-dir discard-dir))))
              (is (= 0 (times-called process-counter))))))))

    (testing "should be discarded if incorrectly formed"
      (let [command (dissoc command :payload)
            process-counter (call-counter)]
        (with-redefs [process-command! process-counter]
          (test-msg-handler command publish discard-dir
            (is (= 0 (times-called publish)))
            (is (= 1 (count (fs/list-dir discard-dir))))
            (is (= 0 (times-called process-counter)))))))))

(deftest command-retry-handler
  (testing "Retry handler"
    (with-redefs [metrics.meters/mark!  (call-counter)
                  annotate-with-attempt (call-counter)]

      (testing "should log errors"
        (let [make-cmd (fn [n] {:command nil :version nil :annotations {:attempts (repeat n {})}})
              publish  (call-counter)]

          (testing "to DEBUG for initial retries"
            (let [log-output (atom [])]
              (binding [*logger-factory* (atom-logger log-output)]
                (handle-command-retry (make-cmd 1) nil publish))

              (is (= (get-in @log-output [0 1]) :debug))))

          (testing "to ERROR for later retries"
            (let [log-output (atom [])]
              (binding [*logger-factory* (atom-logger log-output)]
                (handle-command-retry (make-cmd maximum-allowable-retries) nil publish))

              (is (= (get-in @log-output [0 1]) :error)))))))))

;; The two different versions of replace-catalog have exactly the same
;; behavior, except different inputs.
(deftest replace-catalog
  (doseq [[command-version catalog] {1 (get-in wire-catalogs [1 :empty])
                                     2 (get-in wire-catalogs [2 :empty])}]
    (testing (str (command-names :replace-catalog) " " command-version)
      (let [command      {:command (command-names :replace-catalog)
                          :version command-version
                          :payload (json/generate-string catalog)}
            catalog-hash (shash/catalog-similarity-hash (catalog/parse-catalog catalog command-version))
            certname     (get-in catalog [:data :name])
            one-day      (* 24 60 60 1000)
            yesterday    (to-timestamp (- (System/currentTimeMillis) one-day))
            tomorrow     (to-timestamp (+ (System/currentTimeMillis) one-day))]

        (testing "with no catalog should store the catalog"
          (with-fixtures
            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname FROM certname_catalogs")
                     [{:certname certname}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "with an existing catalog should replace the catalog"
          (with-fixtures
            (sql/insert-record :certnames {:name certname})
            (let [catalog-id (:id (sql/insert-values :catalogs [:hash :api_version :catalog_version] ["some_catalog_hash_existing" 1 "foo"]))]
              (sql/insert-record :certname_catalogs {:certname certname :catalog_id catalog-id}))

            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname, c.hash as catalog FROM certname_catalogs cc, catalogs c WHERE cc.catalog_id=c.id")
                     [{:certname certname :catalog catalog-hash}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "when replacing a catalog with a debug directory, should write out catalogs for inspection"
          (with-fixtures
            (sql/insert-record :certnames {:name certname})
            
            (let [debug-dir (fs/absolute-path (temp-dir))
                  catalog-id (:id (sql/insert-values :catalogs [:hash :api_version :catalog_version] ["some_catalog_hash" 1 "foo"]))]

            (sql/insert-record :certname_catalogs {:certname certname :catalog_id catalog-id})

              (is (nil? (fs/list-dir debug-dir)))
              (test-msg-handler-with-opts command publish discard-dir {:catalog-hash-debug-dir debug-dir}
                (is (= (query-to-vec "SELECT certname, c.hash as catalog FROM certname_catalogs cc, catalogs c WHERE cc.catalog_id=c.id")
                     [{:certname certname :catalog catalog-hash}]))                
                (is (= 5 (count (fs/list-dir debug-dir))))
                (is (= 0 (times-called publish)))
                (is (empty? (fs/list-dir discard-dir)))))))

        (let [command {:command (command-names :replace-catalog)
                       :version command-version
                       :payload "bad stuff"}]
          (testing "with a bad payload should discard the message"
            (with-fixtures
              (test-msg-handler command publish discard-dir
                (is (empty? (query-to-vec "SELECT * FROM certname_catalogs")))
                (is (= 0 (times-called publish)))
                (is (seq (fs/list-dir discard-dir)))))))

        (testing "with a newer catalog should ignore the message"
          (with-fixtures
            (sql/insert-record :certnames {:name certname})
            (sql/insert-record :catalogs {:id 1 :hash "some_catalog_hash_newer" :api_version 1 :catalog_version "foo"})
            (sql/insert-record :certname_catalogs {:certname certname :catalog_id 1 :timestamp tomorrow})

            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname, c.hash as catalog FROM certname_catalogs cc, catalogs c WHERE cc.catalog_id=c.id")
                     [{:certname certname :catalog "some_catalog_hash_newer"}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))


        (testing "should reactivate the node if it was deactivated before the message"
          (with-fixtures
            (sql/insert-record :certnames {:name certname :deactivated yesterday})
            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                     [{:name certname :deactivated nil}]))
              (is (= (query-to-vec "SELECT certname, c.hash as catalog FROM certname_catalogs cc, catalogs c WHERE cc.catalog_id=c.id")
                     [{:certname certname :catalog catalog-hash}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "should store the catalog if the node was deactivated after the message"
          (scf-store/delete-certname! certname)
          (sql/insert-record :certnames {:name certname :deactivated tomorrow})
          (test-msg-handler command publish discard-dir
                            (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                                   [{:name certname :deactivated tomorrow}]))
                            (is (= (query-to-vec "SELECT certname, c.hash as catalog FROM certname_catalogs cc, catalogs c WHERE cc.catalog_id=c.id")
                                   [{:certname certname :catalog catalog-hash}]))
                            (is (= 0 (times-called publish)))
                            (is (empty? (fs/list-dir discard-dir)))))))))

(let [certname  "foo.example.com"
      facts     {:name certname
                 :values {"a" "1"
                          "b" "2"
                          "c" "3"}}
      command   {:command (command-names :replace-facts)
                 :version 1
                 :payload (json/generate-string facts)}
      one-day   (* 24 60 60 1000)
      yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
      tomorrow  (to-timestamp (+ (System/currentTimeMillis) one-day))]

  (deftest replace-facts-no-facts
    (testing "should store the facts"
      (test-msg-handler command publish discard-dir
        (is (= (query-to-vec "SELECT certname,name,value FROM certname_facts ORDER BY name ASC")
               [{:certname certname :name "a" :value "1"}
                {:certname certname :name "b" :value "2"}
                {:certname certname :name "c" :value "3"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir))))))

  (deftest replace-facts-existing-facts
    (sql/insert-record :certnames {:name certname})
    (sql/insert-record :certname_facts_metadata
      {:certname certname :timestamp yesterday})
    (sql/insert-records :certname_facts
      {:certname certname :name "x" :value "24"}
      {:certname certname :name "y" :value "25"}
      {:certname certname :name "z" :value "26"})

    (testing "should replace the facts"
      (test-msg-handler command publish discard-dir
        (let [[result & _] (query-to-vec "SELECT certname,timestamp FROM certname_facts_metadata")]
          (is (= (:certname result)
                 certname))
          (is (not= (:timestamp result)
                    yesterday)))

        (is (= (query-to-vec "SELECT certname,name,value FROM certname_facts ORDER BY name ASC")
               [{:certname certname :name "a" :value "1"}
                {:certname certname :name "b" :value "2"}
                {:certname certname :name "c" :value "3"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir))))))

  (deftest replace-facts-bad-payload
    (let [command {:command (command-names :replace-facts)
                   :version 1
                   :payload "bad stuff"}]
      (testing "should discard the message"
      (test-msg-handler command publish discard-dir
        (is (empty? (query-to-vec "SELECT * FROM certname_facts")))
        (is (= 0 (times-called publish)))
        (is (seq (fs/list-dir discard-dir)))))))

  (deftest replace-facts-newer-facts
    (sql/insert-record :certnames {:name certname})
    (sql/insert-record :certname_facts_metadata
      {:certname certname :timestamp tomorrow})
    (sql/insert-records :certname_facts
      {:certname certname :name "x" :value "24"}
      {:certname certname :name "y" :value "25"}
      {:certname certname :name "z" :value "26"})

    (testing "should ignore the message"
      (test-msg-handler command publish discard-dir
        (is (= (query-to-vec "SELECT certname,timestamp FROM certname_facts_metadata")
               [{:certname certname :timestamp tomorrow}]))
        (is (= (query-to-vec "SELECT certname,name,value FROM certname_facts ORDER BY name ASC")
               [{:certname certname :name "x" :value "24"}
                {:certname certname :name "y" :value "25"}
                {:certname certname :name "z" :value "26"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir))))))

  (deftest replace-facts-deactivated-node-facts

    (testing "should reactivate the node if it was deactivated before the message"
      (sql/insert-record :certnames {:name certname :deactivated yesterday})
      (test-msg-handler command publish discard-dir
        (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
               [{:name certname :deactivated nil}]))
        (is (= (query-to-vec "SELECT certname,name,value FROM certname_facts ORDER BY name ASC")
               [{:certname certname :name "a" :value "1"}
                {:certname certname :name "b" :value "2"}
                {:certname certname :name "c" :value "3"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))

    (testing "should store the facts if the node was deactivated after the message"
      (scf-store/delete-certname! certname)
      (sql/insert-record :certnames {:name certname :deactivated tomorrow})
      (test-msg-handler command publish discard-dir
        (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
               [{:name certname :deactivated tomorrow}]))
        (is (= (query-to-vec "SELECT certname,name,value FROM certname_facts ORDER BY name ASC")
              [{:certname certname :name "a" :value "1"}
               {:certname certname :name "b" :value "2"}
               {:certname certname :name "c" :value "3"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest concurrent-fact-updates
  (testing "Should allow only one replace facts update for a given cert at a time"
    (let [certname "some_certname"
          facts {:name certname
                 :values {"domain" "mydomain.com"
                          "fqdn" "myhost.mydomain.com"
                          "hostname" "myhost"
                          "kernel" "Linux"
                          "operatingsystem" "Debian"}}
          command   {:command (command-names :replace-facts)
                     :version 1
                     :payload (json/generate-string facts)}
          
          hand-off-queue (java.util.concurrent.SynchronousQueue.)
          storage-replace-facts! scf-store/update-facts!]

      (sql/transaction
       (scf-store/add-certname! certname)
       (scf-store/add-facts! certname (:values facts) (-> 2 days ago)))

      (let [first-message? (atom false)
            second-message? (atom false)
            fut (future
                  (with-redefs [scf-store/update-facts! (fn [certname facts timestamp]
                                                          (.put hand-off-queue "got the lock")
                                                          (.poll hand-off-queue 10 java.util.concurrent.TimeUnit/SECONDS)
                                                          (storage-replace-facts! certname (:values facts) timestamp))]
                    (test-msg-handler command publish discard-dir
                      (reset! first-message? true))))

            _ (.poll hand-off-queue 10 java.util.concurrent.TimeUnit/SECONDS)

            new-facts (update-in facts [:values] (fn [values]
                                                   (-> values
                                                       (dissoc "kernel")
                                                       (assoc "newfact2" "here"))))
            new-facts-cmd {:command (command-names :replace-facts)
                           :version 1
                           :payload (json/generate-string new-facts)}]

        (test-msg-handler new-facts-cmd publish discard-dir
          (reset! second-message? true)
          (is (re-matches #".*BatchUpdateException.*(rollback|abort).*"
                          (-> publish
                              meta
                              :args
                              deref
                              ffirst
                              json/parse-string
                              (get-in ["annotations" "attempts"])
                              first
                              (get "error")))))
        @fut
        (is (true? @first-message?))
        (is (true? @second-message?))))))

(let [certname "foo.example.com"
      command {:command (command-names :deactivate-node)
               :version 1
               :payload (json/generate-string certname)}]
  (deftest deactivate-node-node-active
    (sql/insert-record :certnames {:name certname})

    (testing "should deactivate the node"
      (test-msg-handler command publish discard-dir
        (let [results (query-to-vec "SELECT name,deactivated FROM certnames")
              result  (first results)]
          (is (= (:name result) certname))
          (is (instance? java.sql.Timestamp (:deactivated result)))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest deactivate-node-node-inactive
    (let [one-day   (* 24 60 60 1000)
          yesterday (to-timestamp (- (System/currentTimeMillis) one-day))]
      (sql/insert-record :certnames {:name certname :deactivated yesterday})

      (testing "should leave the node alone"
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                 [{:name certname :deactivated yesterday}]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest deactivate-node-node-missing
    (testing "should add the node and deactivate it"
      (test-msg-handler command publish discard-dir
        (let [results (query-to-vec "SELECT name,deactivated FROM certnames")
              result  (first results)]
          (is (= (:name result) certname ))
          (is (instance? java.sql.Timestamp (:deactivated result)))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

(let [report       (munge-example-report-for-storage (:basic report-examples/reports))
      command      {:command (command-names :store-report)
                    :version 2
                    :payload report}]
  (deftest store-report
    (testing "should store the report"
      (test-msg-handler command publish discard-dir
        (is (= (query-to-vec "SELECT certname,configuration_version FROM reports")
              [{:certname (:certname report) :configuration_version (:configuration-version report)}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))


;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (test-msg-handler (quote defun))
;;                              (test-msg-handler-with-opts (quote defun)))
;; End:
