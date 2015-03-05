(ns com.puppetlabs.puppetdb.test.command
  (:require [fs.core :as fs]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.catalogs :as catalog]
            [com.puppetlabs.puppetdb.examples.reports :as report-examples]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger]]
            [clj-time.format :as tfmt]
            [clojure.walk :as walk]
            [com.puppetlabs.puppetdb.query :refer [remove-environment]]
            [com.puppetlabs.puppetdb.command :refer :all]
            [com.puppetlabs.puppetdb.testutils :refer :all]
            [com.puppetlabs.puppetdb.fixtures :refer :all]
            [com.puppetlabs.jdbc :refer [query-to-vec]]
            [com.puppetlabs.puppetdb.examples :refer :all]
            [com.puppetlabs.puppetdb.testutils.reports :refer [munge-example-report-for-storage]]
            [com.puppetlabs.puppetdb.command.constants :refer [command-names]]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :refer [days ago now]]
            [clojure.test :refer :all]
            [clojure.tools.logging :refer [*logger-factory*]]
            [slingshot.slingshot :refer [try+ throw+]]))

(use-fixtures :each with-test-db)

(deftest command-assembly
  (testing "Formatting commands for submission"
    (is (= (assemble-command "my command" 1 [1 2 3 4 5])
           {:command "my command"
            :version 1
            :payload [1 2 3 4 5]}))))

(deftest command-parsing
  (testing "Command parsing"

    (let [command {:body "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}"}]
      (testing "should work for strings"
        (let [parsed (parse-command command)]
          ;; :annotations will have a :attempts element with a time, which
          ;; is hard to test, so disregard that
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed)))))

      (testing "should work for byte arrays"
        (let [parsed (parse-command (update-in command [:body] #(.getBytes % "UTF-8")))]
          (is (= (dissoc parsed :annotations)
                 {:command "foo" :version 2 :payload "meh"}))
          (is (map? (:annotations parsed))))))

    (testing "should reject invalid input"
      (is (thrown? AssertionError (parse-command {:body ""})))
      (is (thrown? AssertionError (parse-command {:body "{}"})))

      ;; Missing required attributes
      (is (thrown? AssertionError (parse-command {:body "{\"version\": 2, \"payload\": \"meh\"}"})))
      (is (thrown? AssertionError (parse-command {:body "{\"version\": 2}"})))

      ;; Non-numeric version
      (is (thrown? AssertionError (parse-command {:body "{\"version\": \"2\", \"payload\": \"meh\"}"})))

      ;; Non-string command
      (is (thrown? AssertionError (parse-command {:body "{\"command\": 123, \"version\": 2, \"payload\": \"meh\"}"})))

      ;; Non-JSON payload
      (is (thrown? Exception (parse-command {:body "{\"command\": \"foo\", \"version\": 2, \"payload\": #{}"})))

      ;; Non-UTF-8 byte array
      (is (thrown? Exception (parse-command {:body (.getBytes "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}" "UTF-16")}))))))

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
        (parser {:body "/s++-"})
        (is (= 0 (times-called called)))
        (is (= 1 (times-called failed)))))

    (testing "should normally pass through a parsed message"
      (let [called (call-counter)
            failed (call-counter)
            parser (wrap-with-command-parser called failed)]
        (parser {:body "{\"command\": \"foo\", \"version\": 2, \"payload\": \"meh\"}"})
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
         msg#            {:headers {:id "foo-id-1"
                                    :received (tfmt/unparse (tfmt/formatters :date-time) (now))}
                          :body (json/generate-string ~command)}]
     (try
       (binding [*logger-factory* (atom-logger log-output#)]
         (handle-message# msg#))
       (let [~publish-var publish#
             ~discard-var discard-dir#]
         ~@body
      ; Uncommenting this line can be very useful for debugging
      ; (println @log-output#)
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
                    published (parse-command {:body msg})
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

(defn make-cmd
  "Create a command pre-loaded with `n` attempts"
  [n]
  {:command nil :version nil :annotations {:attempts (repeat n {})}})

(deftest command-retry-handler
  (testing "Retry handler"
    (with-redefs [metrics.meters/mark!  (call-counter)
                  annotate-with-attempt (call-counter)]

      (testing "should log errors"
        (let [publish  (call-counter)]
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

(deftest test-error-with-stacktrace
  (with-redefs [metrics.meters/mark!  (call-counter)]
    (let [publish  (call-counter)]
      (testing "Exception with stacktrace, no more retries"
        (let [log-output (atom [])]
          (binding [*logger-factory* (atom-logger log-output)]
            (handle-command-retry (make-cmd 1) (RuntimeException. "foo") publish))
          (is (= (get-in @log-output [0 1]) :debug))
          (is (instance? Exception (get-in @log-output [0 2])))))

      (testing "Exception with stacktrace, no more retries"
        (let [log-output (atom [])]
          (binding [*logger-factory* (atom-logger log-output)]
            (handle-command-retry (make-cmd maximum-allowable-retries) (RuntimeException. "foo") publish))
          (is (= (get-in @log-output [0 1]) :error))
          (is (instance? Exception (get-in @log-output [0 2]))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Common functions/macros for support multi-version tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn stringify-payload
  "Converts a clojure payload in the command to the stringified
   JSON structure"
  [catalog]
  (update-in catalog [:payload] json/generate-string))

(defn with-env
  "Returns a function that will update the `row-map` to include
   environment information if `current-version` is in `env-versions`.
   Other versions will not include environment"
  [env-versions]
  (fn [current-version row-map]
    (assoc row-map
      :environment_id (when (contains? env-versions current-version)
                        (scf-store/environment-id "DEV")))))

(def with-fact-env
  "Function that will add the environment_id when testing v2 facts commands"
  (with-env #{:v2 :v3}))
(def with-catalog-env
  "Function that will add the environment_id when testing v5 or v4 catalog commands"
  (with-env #{:v5 :v4}))
(def with-report-env
  "Function that will add the environment_id when testing v3 report commands"
  (with-env #{:v3}))

(defn munge-command
  "Returns a function that will call `default-munge-fn` or `munge-fn` (if supplied)
   to get the appropriate command munging function for the given `target-version`.
   The function return from that will get passed the command for changing to the
   `target-version`"
  [default-munge-fn]
  (fn munge
    ([target-version command]
       (munge target-version command default-munge-fn))
    ([target-version command munge-fn]
       ((munge-fn target-version) command))))

(defn version-num
  "Converts a version keyword into a correct number (expected by the command).
   i.e. :v4 -> 4"
  [version-kwd]
  (-> version-kwd
      name
      last
      Character/getNumericValue))

(defn munge-version
  "Updates a command to indicate `new-version`"
  [new-version]
  (fn [command]
    (assoc command :version new-version)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Catalog Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def catalog-versions
  "Currently supported catalog versions"
  [:v1 :v2 :v3 :v4 :v5])

(defn v1->v2-catalog-command
  "Converts a version 1 catalog command to version 2"
  [command]
  (-> command
      (assoc :version 2)
      (update-in [:payload] v1->v2-catalog)))

(defn v2->v3-catalog-command
  "Converts a version 2 catalog command to version 3"
  [command]
  (-> command
      (assoc :version 3)
      (update-in [:payload] v2->v3-catalog)))

(defn v3->v4-catalog-command
  "Converts a version 3 catalog command to version 4"
  [command]
  (-> command
      (assoc :version 4
             :payload (v3->v4-catalog (get-in command [:payload])))))

(defn v4->v5-catalog-command
  "Converts a version 4 catalog command to version 5"
  [command]
  (-> command
      (assoc :version 5
             :payload (v4->v5-catalog (get-in command [:payload])))))

(defn default-catalog-munging
  "Returns a function appropriate for converting a command to
   the format needed for `version`"
  [version]
  (case version
    :v1 identity
    :v2 v1->v2-catalog-command
    :v3 v2->v3-catalog-command
    :v4 v3->v4-catalog-command
    v4->v5-catalog-command))

(def munge-catalog-command
  "Convenient command for converting catalogs to a particular version.
   Uses default-catalog-munging by default if a munge function isn't specified"
  (munge-command default-catalog-munging))

(def v1-catalog-command
  "Creates a small version 1 catalog command for testing"
  {:command (command-names :replace-catalog)
   :version 1
   :payload (get-in wire-catalogs [1 :empty])})

;; The two different versions of replace-catalog have exactly the same
;; behavior, except different inputs.
(deftest replace-catalog
  (doverseq [version catalog-versions
             :let [command (munge-catalog-command version v1-catalog-command)]]
    (testing (str (command-names :replace-catalog) " " version)
      (let [certname  (if (contains? #{:v4 :v5} version)
                        (get-in command [:payload :name])
                        (get-in command [:payload :data :name])
                        )
            catalog-hash (shash/catalog-similarity-hash
                           (catalog/parse-catalog (:payload command) (version-num version)))
            command (stringify-payload command)
            one-day      (* 24 60 60 1000)
            yesterday    (to-timestamp (- (System/currentTimeMillis) one-day))
            tomorrow     (to-timestamp (+ (System/currentTimeMillis) one-day))]

        (testing "with no catalog should store the catalog"
          (with-fixtures
            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname, environment_id FROM catalogs")
                     [(with-catalog-env version {:certname certname})]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "with an existing catalog should replace the catalog"
          (with-fixtures
            (is (= (query-to-vec "SELECT certname FROM catalogs")
                   []))
            (sql/insert-record :certnames {:name certname})
            (sql/insert-records :catalogs
                                {:hash "some_catalog_hash_existing"
                                 :api_version 1
                                 :catalog_version "foo"
                                 :certname certname})

            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname, hash as catalog, environment_id FROM catalogs")
                     [(with-catalog-env version {:certname certname :catalog catalog-hash})]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "when replacing a catalog with a debug directory, should write out catalogs for inspection"
          (with-fixtures
            (sql/insert-record :certnames {:name certname})

            (let [debug-dir (fs/absolute-path (temp-dir))]

              (sql/insert-records :catalogs {:hash "some_catalog_hash"
                                             :api_version 1
                                             :catalog_version "foo"
                                             :certname certname})

              (is (nil? (fs/list-dir debug-dir)))
              (test-msg-handler-with-opts command publish discard-dir {:catalog-hash-debug-dir debug-dir}
                (is (= (query-to-vec "SELECT certname, hash as catalog, environment_id FROM catalogs")
                       [(with-catalog-env version {:certname certname :catalog catalog-hash})]))
                (is (= 5 (count (fs/list-dir debug-dir))))
                (is (= 0 (times-called publish)))
                (is (empty? (fs/list-dir discard-dir)))))))

        (let [command (assoc command :payload "bad stuff")]
          (testing "with a bad payload should discard the message"
            (with-fixtures
              (test-msg-handler command publish discard-dir
                (is (empty? (query-to-vec "SELECT * FROM catalogs")))
                (is (= 0 (times-called publish)))
                (is (seq (fs/list-dir discard-dir)))))))

        (testing "with a newer catalog should ignore the message"
          (with-fixtures
            (sql/insert-record :certnames {:name certname})
            (sql/insert-record :catalogs {:id 1
                                          :hash "some_catalog_hash_newer"
                                          :api_version 1
                                          :catalog_version "foo"
                                          :certname certname
                                          :timestamp tomorrow})

            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT certname, hash as catalog FROM catalogs")
                     [{:certname certname :catalog "some_catalog_hash_newer"}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))


        (testing "should reactivate the node if it was deactivated before the message"
          (with-fixtures
            (sql/insert-record :certnames {:name certname :deactivated yesterday})
            (test-msg-handler command publish discard-dir
              (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                     [{:name certname :deactivated nil}]))
              (is (= (query-to-vec "SELECT certname, hash as catalog FROM catalogs")
                     [{:certname certname :catalog catalog-hash}]))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "should store the catalog if the node was deactivated after the message"
          (scf-store/delete-certname! certname)
          (sql/insert-record :certnames {:name certname :deactivated tomorrow})
          (test-msg-handler command publish discard-dir
            (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                   [{:name certname :deactivated tomorrow}]))
            (is (= (query-to-vec "SELECT certname, hash as catalog FROM catalogs")
                   [{:certname certname :catalog catalog-hash}]))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))))))))

(defn update-resource
  "Updated the resource in `catalog` with the given `type` and `title`.
   `update-fn` is a function that accecpts the resource map as an argument
   and returns a (possibly mutated) resource map."
  [version catalog type title update-fn]
  (let [path (if (contains? #{:v4 :v5} version)
               [:payload :resources]
               [:payload :data :resources])]
    (update-in catalog path
               (fn [resources]
                 (mapv (fn [res]
                         (if (and (= (:title res) title)
                                  (= (:type res) type))
                           (update-fn res)
                           res))
                       resources)))))

(def basic-wire-catalog
  (get-in wire-catalogs [2 :basic]))

(deftest catalog-with-updated-resource-line
  (doverseq [version catalog-versions
             :let [command (munge-catalog-command version {:command (command-names :replace-catalog)
                                                           :version 2
                                                           :payload basic-wire-catalog})
                   command-1 (stringify-payload command)
                   command-2 (stringify-payload (update-resource version command "File" "/etc/foobar" #(assoc % :line 20)))]]
    (test-msg-handler command-1 publish discard-dir
      (let [orig-resources (scf-store/catalog-resources (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))]
        (is (= 10
               (get-in orig-resources [{:type "File" :title "/etc/foobar"} :line])))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))

        (test-msg-handler command-2 publish discard-dir
          (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :line] 20)
                 (scf-store/catalog-resources
                   (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

(deftest catalog-with-updated-resource-file
  (doverseq [version catalog-versions
             :let [command (munge-catalog-command version {:command (command-names :replace-catalog)
                                                           :version 2
                                                           :payload basic-wire-catalog})
                   command-1 (stringify-payload command)
                   command-2 (stringify-payload (update-resource version command "File" "/etc/foobar" #(assoc % :file "/tmp/not-foo")))]]
    (test-msg-handler command-1 publish discard-dir
      (let [orig-resources (scf-store/catalog-resources (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))]
        (is (= "/tmp/foo"
               (get-in orig-resources [{:type "File" :title "/etc/foobar"} :file])))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))

        (test-msg-handler command-2 publish discard-dir
          (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :file] "/tmp/not-foo")
                 (scf-store/catalog-resources (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

(deftest catalog-with-updated-resource-exported
  (doverseq [version catalog-versions
             :let [command (munge-catalog-command version {:command (command-names :replace-catalog) :version 2 :payload basic-wire-catalog})
                   command-1 (stringify-payload command)
                   command-2 (stringify-payload (update-resource version command "File" "/etc/foobar" #(assoc % :exported true)))]]
    (test-msg-handler command-1 publish discard-dir
      (let [orig-resources (scf-store/catalog-resources (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))]
        (is (= false
               (get-in orig-resources [{:type "File" :title "/etc/foobar"} :exported])))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))

        (test-msg-handler command-2 publish discard-dir
          (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :exported] true)
                 (scf-store/catalog-resources (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))


(deftest catalog-with-updated-resource-tags
  (doverseq [version catalog-versions
             :let [command (munge-catalog-command
                             version {:command (command-names :replace-catalog)
                                      :version 2
                                      :payload basic-wire-catalog})
                   command-1 (stringify-payload command)
                   command-2 (stringify-payload
                               (update-resource version command "File" "/etc/foobar"
                                                #(-> %(assoc :tags #{"file" "class" "foobar" "foo"})
                                                      (assoc :line 20))))]]
    (test-msg-handler command-1 publish discard-dir
      (let [orig-resources (scf-store/catalog-resources
                             (:id (scf-store/catalog-metadata "basic.wire-catalogs.com")))]
        (is (= #{"file" "class" "foobar"}
               (get-in orig-resources [{:type "File" :title "/etc/foobar"} :tags])))
        (is (= 10
               (get-in orig-resources [{:type "File" :title "/etc/foobar"} :line])))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))

        (test-msg-handler command-2 publish discard-dir
          (is (= (-> orig-resources
                     (assoc-in [{:type "File" :title "/etc/foobar"} :tags]
                               #{"file" "class" "foobar" "foo"})
                     (assoc-in [{:type "File" :title "/etc/foobar"} :line] 20))
                 (scf-store/catalog-resources (:id (scf-store/catalog-metadata
                                                     "basic.wire-catalogs.com")))))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Fact Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def fact-versions
  "Support fact command versions"
  [:v1 :v2 :v3])

(defn v2-fact-munge
  "Converts a v1 fact command into a v2 fact command"
  [facts]
  (-> facts
      (assoc :version 2)
      (assoc-in [:payload :environment] "DEV")))

(defn default-fact-munging
  "Converts the command to the `target-version`. For :v1, stringifies the payload
   like the command expects."
  [target-version]
  (case target-version
    :v1 stringify-payload
    v2-fact-munge))

(defn munge-version-only
  "Only changes the version to `target-version` of the given command"
  [target-version]
  (case target-version
    :v1 (munge-version 1)
    (munge-version 2)))

(def munge-fact-command
  "Function for converting fact commands to the specified version, uses
   default-fact-munging to make the conversion unless a different function
   is specified"
  (munge-command default-fact-munging))

(let [certname  "foo.example.com"
      facts     {:name certname
                 :values {"a" "1"
                          "b" "2"
                          "c" "3"}}
      v1-command {:command (command-names :replace-facts)
                  :version 1
                  :payload facts}
      one-day   (* 24 60 60 1000)
      yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
      tomorrow  (to-timestamp (+ (System/currentTimeMillis) one-day))]

  (deftest replace-facts-no-facts
    (doverseq [version fact-versions
               :let [command (munge-fact-command version v1-command)]]

      (testing "should store the facts"
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec
                  "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
                 [{:certname certname :name "a" :value "1"}
                  {:certname certname :name "b" :value "2"}
                  {:certname certname :name "c" :value "3"}]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))
          (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
            (is (= result [(with-fact-env version {:certname certname})])))))))

  (deftest replace-facts-existing-facts
    (doverseq [version fact-versions
               :let [command (munge-fact-command version v1-command)]]

      (sql/transaction
       (scf-store/ensure-environment "DEV")
       (scf-store/add-certname! certname)
       (scf-store/replace-facts! {:name certname
                                  :values {"x" "24" "y" "25" "z" "26"}
                                  :timestamp yesterday
                                  :producer-timestamp yesterday
                                  :environment (when (not= version :v1) "DEV")}))

      (testing "should replace the facts"
        (test-msg-handler command publish discard-dir
          (let [[result & _] (query-to-vec "SELECT certname,timestamp, environment_id FROM factsets")]
            (is (= (:certname result)
                   certname))
            (is (not= (:timestamp result)
                      yesterday))
            (when-not (= version :v1)
              (is (= (scf-store/environment-id "DEV") (:environment_id result)))))

          (is (= (query-to-vec
                  "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                   WHERE fp.depth = 0
                   ORDER BY fp.path ASC")
                 [{:certname certname :name "a" :value "1"}
                  {:certname certname :name "b" :value "2"}
                  {:certname certname :name "c" :value "3"}]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest replace-facts-newer-facts
    (doverseq [version fact-versions
               :let [command (munge-fact-command version v1-command)]]

      (sql/transaction
       (scf-store/ensure-environment "DEV")
       (scf-store/add-certname! certname)
       (scf-store/add-facts! {:name certname
                              :values {"x" "24" "y" "25" "z" "26"}
                              :timestamp tomorrow
                              :producer-timestamp nil
                              :environment (when (not= version :v1) "DEV")}))

      (testing "should ignore the message"
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec "SELECT certname,timestamp,environment_id FROM factsets")
                 [(with-fact-env version {:certname certname :timestamp tomorrow})]))
          (is (= (query-to-vec
                  "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
                 [{:certname certname :name "x" :value "24"}
                  {:certname certname :name "y" :value "25"}
                  {:certname certname :name "z" :value "26"}]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest replace-facts-deactivated-node-facts
    (doverseq [version fact-versions
               :let [command (munge-fact-command version v1-command)]]

      (testing "should reactivate the node if it was deactivated before the message"
        (sql/insert-record :certnames {:name certname :deactivated yesterday})
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec "SELECT name,deactivated FROM certnames")
                 [{:name certname :deactivated nil}]))
          (is (= (query-to-vec
                  "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
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
          (is (= (query-to-vec
                  "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
                 [{:certname certname :name "a" :value "1"}
                  {:certname certname :name "b" :value "2"}
                  {:certname certname :name "c" :value "3"}]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

(deftest replace-facts-bad-payload
  (let [bad-command {:command (command-names :replace-facts)
                     :version 1
                     :payload "bad stuff"}]
    (doverseq [version fact-versions
               :let [command (munge-fact-command version bad-command munge-version-only)]]
      (testing "should discard the message"
        (test-msg-handler command publish discard-dir
          (is (empty? (query-to-vec "SELECT * FROM facts")))
          (is (= 0 (times-called publish)))
          (is (seq (fs/list-dir discard-dir))))))))

(defn extract-error-message
  "Pulls the error message from the publish var of a test-msg-handler"
  [publish]
  (-> publish
      meta
      :args
      deref
      ffirst
      json/parse-string
      (get-in ["annotations" "attempts"])
      first
      (get "error")))

(deftest concurrent-fact-updates
  (testing "Should allow only one replace facts update for a given cert at a time"
    (let [certname "some_certname"
          facts {:name certname
                 :environment "DEV"
                 :values {"domain" "mydomain.com"
                          "fqdn" "myhost.mydomain.com"
                          "hostname" "myhost"
                          "kernel" "Linux"
                          "operatingsystem" "Debian"}}
          command   {:command (command-names :replace-facts)
                     :version 2
                     :payload facts}

          hand-off-queue (java.util.concurrent.SynchronousQueue.)
          storage-replace-facts! scf-store/update-facts!]

      (sql/transaction
       (scf-store/add-certname! certname)
       (scf-store/add-facts! {:name certname
                              :values (:values facts)
                              :timestamp (-> 2 days ago)
                              :environment nil
                              :producer-timestamp nil}))

      (with-redefs [scf-store/update-facts! (fn [fact-data]
                                              (.put hand-off-queue "got the lock")
                                              (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)
                                              (storage-replace-facts! fact-data))]
        (let [first-message? (atom false)
              second-message? (atom false)
              fut (future
                    (test-msg-handler command publish discard-dir
                      (reset! first-message? true)))

              _ (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)

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
                            (extract-error-message publish))))
          @fut
          (is (true? @first-message?))
          (is (true? @second-message?)))))))

(deftest concurrent-catalog-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (let [test-catalog (get-in catalogs [:empty])
          wire-catalog (get-in wire-catalogs [2 :empty])
          nonwire-catalog (catalog/parse-catalog wire-catalog 3)
          certname     (get-in wire-catalog [:data :name])
          command {:command (command-names :replace-catalog)
                   :version 3
                   :payload (json/generate-string wire-catalog)}

          hand-off-queue (java.util.concurrent.SynchronousQueue.)
          storage-replace-catalog! scf-store/replace-catalog!]

      (sql/transaction
       (scf-store/add-certname! certname)
       (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

      (with-redefs [scf-store/replace-catalog! (fn [catalog timestamp dir]
                                                 (.put hand-off-queue "got the lock")
                                                 (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)
                                                 (storage-replace-catalog! catalog timestamp dir))]
        (let [first-message? (atom false)
              second-message? (atom false)
              fut (future
                    (test-msg-handler command publish discard-dir
                      (reset! first-message? true)))

              _ (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)

              new-wire-catalog (assoc-in wire-catalog [:data :edges]
                                 #{{:relationship "contains"
                                    :target       {:title "Settings" :type "Class"}
                                    :source       {:title "main" :type "Stage"}}})
              new-catalog-cmd {:command (command-names :replace-catalog)
                               :version 3
                               :payload (json/generate-string new-wire-catalog)}]

          (test-msg-handler new-catalog-cmd publish discard-dir
            (reset! second-message? true)
            (is (empty? (fs/list-dir discard-dir)))
            (is (re-matches #".*BatchUpdateException.*(rollback|abort).*"
                            (extract-error-message publish))))
          @fut
          (is (true? @first-message?))
          (is (true? @second-message?)))))))

(deftest concurrent-catalog-resource-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (let [test-catalog (get-in catalogs [:empty])
          wire-catalog (get-in wire-catalogs [2 :empty])
          nonwire-catalog (catalog/parse-catalog wire-catalog 3)
          certname     (get-in wire-catalog [:data :name])
          command {:command (command-names :replace-catalog)
                   :version 3
                   :payload (json/generate-string wire-catalog)}

          hand-off-queue (java.util.concurrent.SynchronousQueue.)
          storage-replace-catalog! scf-store/replace-catalog!]

      (sql/transaction
       (scf-store/add-certname! certname)
       (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

      (with-redefs [scf-store/replace-catalog! (fn [catalog timestamp dir]
                                                 (.put hand-off-queue "got the lock")
                                                 (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)
                                                 (storage-replace-catalog! catalog timestamp dir))]
        (let [first-message? (atom false)
              second-message? (atom false)
              fut (future
                    (test-msg-handler command publish discard-dir
                      (reset! first-message? true)))

              _ (.poll hand-off-queue 5 java.util.concurrent.TimeUnit/SECONDS)

              new-wire-catalog (update-in wire-catalog [:data :resources]
                                          conj
                                          {:type       "File"
                                           :title      "/etc/foobar2"
                                           :exported   false
                                           :file       "/tmp/foo2"
                                           :line       10
                                           :tags       #{"file" "class" "foobar2"}
                                           :parameters {:ensure "directory"
                                                        :group  "root"
                                                        :user   "root"}})
              new-catalog-cmd {:command (command-names :replace-catalog)
                               :version 3
                               :payload (json/generate-string new-wire-catalog)}]

          (test-msg-handler new-catalog-cmd publish discard-dir
            (reset! second-message? true)
            (is (empty? (fs/list-dir discard-dir)))
            (is (re-matches #".*BatchUpdateException.*(rollback|abort).*"
                            (extract-error-message publish))))
          @fut
          (is (true? @first-message?))
          (is (true? @second-message?)))))))

(let [certname "foo.example.com"
      command {:command (command-names :deactivate-node)
               :version 2
               :payload certname}]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Report Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def report-versions
  "Report versions supported. Version 1 is not currently being tested."
  [:v2 :v3])

(defn v3-report-munge
  "Adds in the environment property necessary for v3 report commands"
  [report]
  (-> report
      (assoc :version 3)
      (assoc-in [:payload :environment] "DEV")))

(defn default-report-munging
  "Sets the version and makes the changes necessary for the report to support
   the specified `target-version`"
  [target-version]
  (case target-version
    :v1 (munge-version 1)
    :v2 (munge-version 2)
    v3-report-munge))

(def munge-report-command
  "Function for converting the a command to a specified version, using
   default-report-munging if a munge function has not been specified"
  (munge-command default-report-munging))

(let [report (munge-example-report-for-storage (remove-environment (:basic report-examples/reports) :v2))
      v2-command {:command (command-names :store-report)
                  :version 2
                  :payload report}]
  (deftest store-report
    (testing "should store the report"
      (doverseq [version report-versions
                 :let [command (munge-report-command version v2-command)]]
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec "SELECT certname,configuration_version,environment_id FROM reports")
                 [(with-report-env version {:certname (:certname report) :configuration_version (:configuration-version report)})]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (test-msg-handler (quote defun))
;;                              (test-msg-handler-with-opts (quote defun))
;;                              (doverseq (quote defun))))
;; End:
