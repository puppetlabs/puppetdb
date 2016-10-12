(ns puppetlabs.puppetdb.command-test
  (:require [me.raynes.fs :as fs]
            [clj-http.client :as client]
            [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants
             :refer [latest-catalog-version latest-facts-version]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.metrics.core :refer [new-metrics]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.catalogs :as catalog]
            [puppetlabs.puppetdb.examples.reports :as report-examples]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger]]
            [clj-time.format :as tfmt]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.command :refer :all]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.testutils
             :refer [args-supplied call-counter dotestseq times-called mock-fn]]
            [puppetlabs.puppetdb.test-protocols :refer [called?]]
            [puppetlabs.puppetdb.jdbc :refer [query-to-vec] :as jdbc]
            [puppetlabs.puppetdb.jdbc-test :refer [full-sql-exception-msg]]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [clj-time.coerce
             :refer [from-sql-date to-timestamp to-date-time to-string]]
            [clj-time.core :as t :refer [days ago now seconds]]
            [clojure.test :refer :all]
            [clojure.tools.logging :refer [*logger-factory*]]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.mq-listener :as mql]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.time :as pt]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.string :as str]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.testutils.nio :as nio]
            [puppetlabs.puppetdb.testutils.queue :as tqueue]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.trapperkeeper.services
             :refer [service-context]])
  (:import [java.util.concurrent TimeUnit]
           [org.joda.time DateTime DateTimeZone]))

(defn unroll-old-command [{:keys [command version payload]}]
  [command
   version
   (or (:certname payload)
       (:name payload))
   payload])

(defmacro test-msg-handler*
  [command delay-var discard-var db & body]
  `(tqueue/with-stockpile q#
     (let [log-output#     (atom [])
           delay# (call-counter)
           dlo-dir# (fs/temp-dir "test-msg-handler-dlo")
           dlo# (dlo/initialize (.toPath dlo-dir#)
                                (:registry (new-metrics "puppetlabs.puppetdb.dlo"
                                                        :jmx? false)))
           handle-message# (mql/message-handler q# dlo#
                                                delay#
                                                #(process-command! % ~db))
           cmd# ~command
           cmdref# (-> (apply tqueue/store-command q# (unroll-old-command cmd#))
                       (assoc :attempts (:attempts cmd#)))]
       (try
         (binding [*logger-factory* (atom-logger log-output#)]
           (handle-message# cmdref#))
         (let [~delay-var delay#
               ~discard-var dlo-dir#]
           ~@body
           ;; Uncommenting this line can be very useful for debugging
           ;; (println @log-output#)
           )
         (finally
           (fs/delete-dir dlo-dir#))))))

(defmacro test-msg-handler
  "Converts `command` to JSON, runs it through a message-handler, and
  then evaluates the `body` with `delay-var` bound to a call-counter,
  and `discard-var` bound to the directory that contains failed
  messages.  A history of failed attempts may be provided
  via (:attempts `command`), which must be as a sequence of items
  as created by (cons-attempt exception)."
  [command delay-var discard-var & body]
  `(test-msg-handler* ~command ~delay-var ~discard-var *db* ~@body))

(defn add-fake-attempts [cmdref n]
  (loop [i 0
         result cmdref]
    (if (or (neg? n) (= i n))
      result
      (recur (inc i)
             (queue/cons-attempt result (Exception. (str "thud-" i)))))))

(deftest command-processor-integration
  (let [command {:command "replace catalog" :version 5 :payload "\"payload\""}]
    (testing "correctly formed messages"

      (testing "which are not yet expired"

        (testing "when successful should not raise errors or retry"
          (with-redefs [process-command! (constantly true)]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "when a fatal error occurs should be discarded to the dead letter queue"
          (with-redefs [process-command! (fn [cmd db] (throw+ (fatality (Exception. "fatal error"))))]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (= 2 (count (fs/list-dir discard-dir)))))))

        (testing "when a non-fatal error occurs should be requeued with the error recorded"
          (with-redefs [process-command! (fn [cmd db] (throw+ (Exception. "non-fatal error")))]
            (test-msg-handler command publish discard-dir
              (is (empty? (fs/list-dir discard-dir)))
              (let [[call :as calls] (args-supplied publish)
                    [msg] call
                    attempts (:attempts msg)]
                (is (= 1 (count calls)))
                (is (= 1 (count call)))
                (is (= 1 (count attempts)))
                (is (= "non-fatal error"
                       (-> attempts first :exception .getMessage))))))))

      (testing "should be discarded if expired"
        (let [command (add-fake-attempts command mql/maximum-allowable-retries)
              command (assoc command :version 9)
              process-counter (call-counter)]
          ;; Q: Do we want a RuntimeException here?
          (with-redefs [process-command! (fn [_ _] (throw (RuntimeException. "Expected failure")))]
            (test-msg-handler command publish discard-dir
              (is (= 0 (times-called publish)))
              (is (= 2 (count (fs/list-dir discard-dir))))
              (is (= 0 (times-called process-counter))))))))

    (testing "should be discarded if incorrectly formed"
      (let [command (assoc command :payload "{\"malformed\": \"with no closing brace\"")
            process-counter (call-counter)]
        (with-redefs [json/generate-string identity
                      process-command! process-counter]
          (test-msg-handler command publish discard-dir
            (is (= 0 (times-called publish)))
            (is (= 2 (count (fs/list-dir discard-dir))))
            (is (= 0 (times-called process-counter)))))))))

(deftest command-retry-handler
  (testing "Should log all L2 retries as errors"
    (tqueue/with-stockpile q
      (let [process-message (fn [_] (throw (RuntimeException. "retry me")))]
        (with-redefs [mql/discard-message (mock-fn)]
          (doseq [i (range 0 mql/maximum-allowable-retries)
                  :let [log-output (atom [])
                        delay-message (mock-fn)
                        handle-message (mql/message-handler q nil
                                                            delay-message process-message)]]
            (binding [*logger-factory* (atom-logger log-output)]
              (handle-message (-> (tqueue/store-command q "replace catalog" 10
                                                        "cats" {:certname "cats"})
                                  (add-fake-attempts i)))
              (is (called? delay-message))
              (is (not (called? mql/discard-message)))

              (is (= (get-in @log-output [1 1]) :error))
              (is (str/includes? (get-in @log-output [1 3]) "cats"))
              (is (instance? Exception (get-in @log-output [1 2])))
              (is (str/includes? (last (second @log-output))
                                 "Retrying after L2 attempt")))))

        (let [log-output (atom [])
              delay-message (mock-fn)
              discard-message (mock-fn)
              handle-message (mql/message-handler q nil delay-message process-message)]
          (with-redefs [mql/discard-message (mock-fn)]
            (binding [*logger-factory* (atom-logger log-output)]
              (handle-message (-> (tqueue/store-command q "replace catalog" 10
                                                        "cats" {:certname "cats"})
                                  (add-fake-attempts mql/maximum-allowable-retries)))
              (is (not (called? delay-message)))
              (is (called? mql/discard-message))
              (is (= (get-in @log-output [1 1]) :error))
              (is (instance? Exception (get-in @log-output [1 2])))
              (is (str/includes? (last (second @log-output))
                                 "Exceeded max"))
              (is (str/includes? (get-in @log-output [1 3]) "cats")))))))))

(deftest message-acknowledgement
  (testing "happy path, message acknowledgement when no failures occured"
    (tqueue/with-stockpile q
      (with-redefs [mql/discard-message (fn [& args] true)]
        (let [handle-message (mql/message-handler q nil nil identity)
              cmdref (tqueue/store-command q "replace catalog" 10 "cats" {:certname "cats"})]
          (is (:payload (queue/cmdref->cmd q cmdref)))
          (handle-message cmdref)
          (is (thrown+-with-msg? [:kind :puppetlabs.stockpile.queue/no-such-entry]
                                 #"No file found"
                                 (queue/cmdref->cmd q cmdref)))))))

  (testing "Failures do not cause messages to be acknowledged"
    (tqueue/with-stockpile q
      (with-redefs [mql/discard-message (fn [& args] true)]
        (let [delay-message (mock-fn)
              handle-message (mql/message-handler q nil delay-message
                                                  (fn [_]
                                                    (throw (RuntimeException. "retry me"))))
              entry (tqueue/store-command q "replace catalog" 10 "cats" {:certname "cats"})]
          (is (:payload (queue/cmdref->cmd q entry)))
          (handle-message entry)
          (is (called? delay-message))
          (is (:payload (queue/cmdref->cmd q entry))))))))

(deftest call-with-quick-retry-test
  (testing "errors are logged at debug while retrying"
    (let [log-output (atom [])]
      (binding [*logger-factory* (atom-logger log-output)]
        (try (call-with-quick-retry 1
                                    (fn []
                                      (throw (RuntimeException. "foo"))))
             (catch RuntimeException e nil)))
      (is (= (get-in @log-output [0 1]) :debug))
      (is (instance? Exception (get-in @log-output [0 2])))))

  (testing "retries the specified number of times"
    (let [publish (call-counter)
          num-retries 5
          counter (atom num-retries)]
      (try (call-with-quick-retry num-retries
                                  (fn []
                                    (if (= @counter 0)
                                      (publish)
                                      (do (swap! counter dec)
                                          (throw (RuntimeException. "foo"))))))
           (catch RuntimeException e nil))
      (is (= 1 (times-called publish)))))

  (testing "stops retrying after a success"
    (let [publish (call-counter)
          counter (atom 0)]
      (call-with-quick-retry 5
                             (fn []
                               (swap! counter inc)
                               (publish)))
      (is (= 1 @counter))
      (is (= 1 (times-called publish)))))

  (testing "fatal errors are not retried"
    (let [e (try+ (call-with-quick-retry 0
                                         (fn []
                                           (throw+ (fatality (Exception. "fatal error")))))
                  (catch mql/fatal? e e))]
      (is (= true (:fatal e)))))

  (testing "errors surfaces when no more retries are left"
    (let [e (try (call-with-quick-retry 0
                                        (fn []
                                          (throw (RuntimeException. "foo"))))
                 (catch RuntimeException e e))]
      (is (instance? RuntimeException e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Common functions/macros for support multi-version tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-env
  "Updates the `row-map` to include environment information."
  [row-map]
  (assoc row-map :environment_id (scf-store/environment-id "DEV")))

(defn with-producer
  "Updates the `row-map` to include producer information."
  [row-map]
  (assoc row-map :producer_id (scf-store/producer-id "bar.com")))

(defn version-kwd->num
  "Converts a version keyword into a correct number (expected by the command).
   i.e. :v4 -> 4"
  [version-kwd]
  (-> version-kwd
      name
      last
      Character/getNumericValue))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Catalog Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def catalog-versions
  "Currently supported catalog versions"
  [:v8 :v9])

(deftest replace-catalog-test
  (dotestseq [version catalog-versions
              :let [raw-command {:command (command-names :replace-catalog)
                                 :version (version-kwd->num version)
                                 :payload (-> (get-in wire-catalogs [(version-kwd->num version) :empty])
                                              (assoc :producer_timestamp (now)))}]]
    (testing (str (command-names :replace-catalog) " " version)
      (let [certname (get-in raw-command [:payload :certname])
            catalog-hash (shash/catalog-similarity-hash
                          (catalog/parse-catalog (:payload raw-command) (version-kwd->num version) (now)))
            one-day      (* 24 60 60 1000)
            yesterday    (to-timestamp (- (System/currentTimeMillis) one-day))
            tomorrow     (to-timestamp (+ (System/currentTimeMillis) one-day))]

        (testing "with no catalog should store the catalog"
          (with-test-db
            (test-msg-handler raw-command publish discard-dir
              (is (= [(with-env {:certname certname})]
                     (query-to-vec "SELECT certname, environment_id FROM catalogs")))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "with code-id should store the catalog"
          (with-test-db
            (test-msg-handler
              (-> raw-command
                  (assoc-in [:payload :code_id] "my_git_sha1"))
              publish
              discard-dir
              (is (= [(with-env {:certname certname :code_id "my_git_sha1"})]
                     (query-to-vec "SELECT certname, code_id, environment_id FROM catalogs")))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "with an existing catalog should replace the catalog"
          (with-test-db
            (is (= (query-to-vec "SELECT certname FROM catalogs")
                   []))
            (jdbc/insert! :certnames {:certname certname})
            (jdbc/insert! :catalogs {:hash (sutils/munge-hash-for-storage "00")
                                     :api_version 1
                                     :catalog_version "foo"
                                     :certname certname
                                     :producer_timestamp (to-timestamp (-> 1 days ago))})

            (test-msg-handler raw-command publish discard-dir
              (is (= [(with-env {:certname certname :catalog catalog-hash})]
                     (query-to-vec (format "SELECT certname, %s as catalog, environment_id FROM catalogs"
                                           (sutils/sql-hash-as-str "hash")))))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (let [command (assoc raw-command :payload "bad stuff")]
          (testing "with a bad payload should discard the message"
            (with-test-db
              (test-msg-handler command publish discard-dir
                (is (empty? (query-to-vec "SELECT * FROM catalogs")))
                (is (= 0 (times-called publish)))
                (is (seq (fs/list-dir discard-dir)))))))

        (testing "with a newer catalog should ignore the message"
            (with-test-db
              (jdbc/insert! :certnames {:certname certname})
              (jdbc/insert! :catalogs {:hash (sutils/munge-hash-for-storage "ab")
                                       :api_version 1
                                       :catalog_version "foo"
                                       :certname certname
                                       :timestamp tomorrow
                                       :producer_timestamp (to-timestamp (now))})
              (test-msg-handler raw-command publish discard-dir
                (is (= [{:certname certname :catalog "ab"}]
                       (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                             (sutils/sql-hash-as-str "hash")))))
                (is (= 0 (times-called publish)))
                (is (empty? (fs/list-dir discard-dir))))))

        (testing "should reactivate the node if it was deactivated before the message"
            (with-test-db
              (jdbc/insert! :certnames {:certname certname :deactivated yesterday})
              (test-msg-handler raw-command publish discard-dir
                (is (= [{:certname certname :deactivated nil}]
                       (query-to-vec "SELECT certname,deactivated FROM certnames")))
                (is (= [{:certname certname :catalog catalog-hash}]
                       (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                             (sutils/sql-hash-as-str "hash")))))
                (is (= 0 (times-called publish)))
                (is (empty? (fs/list-dir discard-dir)))))

            (testing "should store the catalog if the node was deactivated after the message"
              (with-test-db
                (scf-store/delete-certname! certname)
                (jdbc/insert! :certnames {:certname certname :deactivated tomorrow})
                (test-msg-handler raw-command publish discard-dir
                  (is (= [{:certname certname :deactivated tomorrow}]
                         (query-to-vec "SELECT certname,deactivated FROM certnames")))
                  (is (= [{:certname certname :catalog catalog-hash}]
                         (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                               (sutils/sql-hash-as-str "hash")))))
                  (is (= 0 (times-called publish)))
                  (is (empty? (fs/list-dir discard-dir)))))))))))

;; If there are messages in the user's MQ when they upgrade, we could
;; potentially have commands of an unsupported format that need to be
;; processed. Although we don't support the catalog versions below, we
;; need to test that those commands will be processed properly
(deftest replace-catalog-with-v6
  (testing "catalog wireformat v6"
    (with-test-db
      (let [command {:command (command-names :replace-catalog)
                     :version 6
                     :payload (get-in wire-catalogs [6 :empty])}
            certname (get-in command [:payload :certname])
            cmd-producer-timestamp (get-in command [:payload :producer_timestamp])]
        (test-msg-handler command publish discard-dir

          ;;names in v5 are hyphenated, this check ensures we're sending a v5 catalog
          (is (contains? (:payload command) :producer_timestamp))
          (is (= [(with-env {:certname certname})]
                 (query-to-vec "SELECT certname, environment_id FROM catalogs")))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))

          ;;this should be the hyphenated producer timestamp provided above
          (is (= (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                     first
                     :producer_timestamp)
                 (to-timestamp cmd-producer-timestamp))))))))

(deftest replace-catalog-with-v5
  (testing "catalog wireformat v5"
    (with-test-db
      (let [command {:command (command-names :replace-catalog)
                     :version 5
                     :payload (get-in wire-catalogs [5 :empty])}
            certname (get-in command [:payload :name])
            cmd-producer-timestamp (get-in command [:payload :producer-timestamp])]
        (test-msg-handler command publish discard-dir

          ;;names in v5 are hyphenated, this check ensures we're sending a v5 catalog
          (is (contains? (:payload command) :producer-timestamp))
          (is (= [(with-env {:certname certname})]
                 (query-to-vec "SELECT certname, environment_id FROM catalogs")))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))

          ;;this should be the hyphenated producer timestamp provided above
          (is (= (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                     first
                     :producer_timestamp)
                 (to-timestamp cmd-producer-timestamp))))))))

(deftest replace-catalog-with-v4
  (with-test-db
    (let [command {:command (command-names :replace-catalog)
                   :version 4
                   :payload (get-in wire-catalogs [4 :empty])}
          certname (get-in command [:payload :name])
          cmd-producer-timestamp (get-in command [:payload :producer-timestamp])
          recent-time (-> 1 seconds ago)]
      (test-msg-handler command publish discard-dir
        (is (false? (contains? (:payload command) :producer-timestamp)))
        (is (= [(with-env {:certname certname})]
               (query-to-vec "SELECT certname, environment_id FROM catalogs")))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))
        ;;v4 does not include a producer_timestmap, the backend
        ;;should use the time the command was received instead
        (is (t/before? recent-time
                       (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                           first
                           :producer_timestamp
                           to-date-time)))))))

(defn update-resource
  "Updated the resource in `catalog` with the given `type` and `title`.
   `update-fn` is a function that accecpts the resource map as an argument
   and returns a (possibly mutated) resource map."
  [version catalog type title update-fn]
  (let [path [:payload :resources]]
    (update-in catalog path
               (fn [resources]
                 (mapv (fn [res]
                         (if (and (= (:title res) title)
                                  (= (:type res) type))
                           (update-fn res)
                           res))
                       resources)))))

(def basic-wire-catalog
  (get-in wire-catalogs [9 :basic]))

(deftest catalog-with-updated-resource-line
  (dotestseq [version catalog-versions
              :let [command-1 {:command (command-names :replace-catalog)
                               :version latest-catalog-version
                               :payload basic-wire-catalog}
                    command-2 (update-resource version command-1 "File" "/etc/foobar"
                                               #(assoc % :line 20))]]
    (with-test-db
      (test-msg-handler command-1 publish discard-dir
        (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                           (scf-store/latest-catalog-metadata
                                                            "basic.wire-catalogs.com")))]
          (is (= 10
                 (get-in orig-resources [{:type "File" :title "/etc/foobar"} :line])))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))

          (test-msg-handler command-2 publish discard-dir
            (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :line] 20)
                   (scf-store/catalog-resources (:certname_id
                                                 (scf-store/latest-catalog-metadata
                                                  "basic.wire-catalogs.com")))))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))))))))

(deftest catalog-with-updated-resource-file
  (dotestseq [version catalog-versions
              :let [command-1 {:command (command-names :replace-catalog)
                               :version latest-catalog-version
                               :payload basic-wire-catalog}
                    command-2 (update-resource version command-1 "File" "/etc/foobar"
                                               #(assoc % :file "/tmp/not-foo"))]]
    (with-test-db
      (test-msg-handler command-1 publish discard-dir
        (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                           (scf-store/latest-catalog-metadata
                                                            "basic.wire-catalogs.com")))]
          (is (= "/tmp/foo"
                 (get-in orig-resources [{:type "File" :title "/etc/foobar"} :file])))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))

          (test-msg-handler command-2 publish discard-dir
            (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :file] "/tmp/not-foo")
                   (scf-store/catalog-resources (:certname_id
                                                 (scf-store/latest-catalog-metadata
                                                  "basic.wire-catalogs.com")))))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))))))))

(deftest catalog-with-updated-resource-exported
  (dotestseq [version catalog-versions
              :let [command-1 {:command (command-names :replace-catalog)
                               :version latest-catalog-version
                               :payload basic-wire-catalog}
                    command-2 (update-resource version command-1 "File" "/etc/foobar"
                                               #(assoc % :exported true))]]
    (with-test-db
      (test-msg-handler command-1 publish discard-dir
        (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                           (scf-store/latest-catalog-metadata
                                                            "basic.wire-catalogs.com")))]
          (is (= false
                 (get-in orig-resources [{:type "File" :title "/etc/foobar"} :exported])))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir)))

          (test-msg-handler command-2 publish discard-dir
            (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :exported] true)
                   (scf-store/catalog-resources (:certname_id
                                                 (scf-store/latest-catalog-metadata
                                                  "basic.wire-catalogs.com")))))))))))

(deftest catalog-with-updated-resource-tags
  (dotestseq [version catalog-versions
              :let [command-1 {:command (command-names :replace-catalog)
                               :version latest-catalog-version
                               :payload basic-wire-catalog}
                    command-2 (update-resource version command-1 "File" "/etc/foobar"
                                               #(assoc %
                                                       :tags #{"file" "class" "foobar" "foo"}
                                                       :line 20))]]
    (with-test-db
      (test-msg-handler command-1 publish discard-dir
        (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                           (scf-store/latest-catalog-metadata
                                                            "basic.wire-catalogs.com")))]
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
                   (scf-store/catalog-resources (:certname_id
                                                 (scf-store/latest-catalog-metadata
                                                  "basic.wire-catalogs.com")))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Fact Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def fact-versions
  "Support fact command versions"
  [:v4])

(let [certname  "foo.example.com"
      facts     {:certname certname
                 :environment "DEV"
                 :values {"a" "1"
                          "b" "2"
                          "c" "3"}
                 :producer_timestamp (to-timestamp (now))}
      v4-command {:command (command-names :replace-facts)
                  :version 4
                  :payload facts}
      one-day   (* 24 60 60 1000)
      yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
      tomorrow  (to-timestamp (+ (System/currentTimeMillis) one-day))]

  (deftest replace-facts-no-facts
    (dotestseq [version fact-versions
                :let [command v4-command]]
      (testing "should store the facts"
        (with-test-db
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
              (is (= result [(with-env {:certname certname})]))))))))

  (deftest replace-facts-existing-facts
    (dotestseq [version fact-versions
                :let [command v4-command]]
      (with-test-db
        (jdbc/with-db-transaction []
          (scf-store/ensure-environment "DEV")
          (scf-store/add-certname! certname)
          (scf-store/replace-facts! {:certname certname
                                     :values {"x" "24" "y" "25" "z" "26"}
                                     :timestamp yesterday
                                     :producer_timestamp yesterday
                                     :producer "bar.com"
                                     :environment "DEV"}))

        (testing "should replace the facts"
          (test-msg-handler command publish discard-dir
            (let [[result & _] (query-to-vec "SELECT certname,timestamp, environment_id FROM factsets")]
              (is (= (:certname result)
                     certname))
              (is (not= (:timestamp result)
                        yesterday))
              (is (= (scf-store/environment-id "DEV") (:environment_id result))))

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
            (is (empty? (fs/list-dir discard-dir))))))))

  (deftest replace-facts-newer-facts
    (dotestseq [version fact-versions
                :let [command v4-command]]
      (with-test-db
        (jdbc/with-db-transaction []
          (scf-store/ensure-environment "DEV")
          (scf-store/add-certname! certname)
          (scf-store/add-facts! {:certname certname
                                 :values {"x" "24" "y" "25" "z" "26"}
                                 :timestamp tomorrow
                                 :producer_timestamp (to-timestamp (now))
                                 :producer "bar.com"
                                 :environment "DEV"}))

        (testing "should ignore the message"
          (test-msg-handler command publish discard-dir
            (is (= (query-to-vec "SELECT certname,timestamp,environment_id FROM factsets")
                   [(with-env {:certname certname :timestamp tomorrow})]))
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
            (is (empty? (fs/list-dir discard-dir))))))))

  (deftest replace-facts-deactivated-node-facts
    (dotestseq [version fact-versions
                :let [command v4-command]]
      (with-test-db
        (testing "should reactivate the node if it was deactivated before the message"
          (jdbc/insert! :certnames {:certname certname :deactivated yesterday})
          (test-msg-handler command publish discard-dir
            (is (= (query-to-vec "SELECT certname,deactivated FROM certnames")
                   [{:certname certname :deactivated nil}]))
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
          (jdbc/insert! :certnames {:certname certname :deactivated tomorrow})
          (test-msg-handler command publish discard-dir
            (is (= (query-to-vec "SELECT certname,deactivated FROM certnames")
                   [{:certname certname :deactivated tomorrow}]))
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
            (is (empty? (fs/list-dir discard-dir)))))))))

;;v2 and v3 fact commands are only supported when commands are still
;;sitting in the queue from before upgrading
(deftest replace-facts-with-v3-wire-format
  (with-test-db
    (let [certname  "foo.example.com"
          producer-time (-> (now)
                            to-timestamp
                            json/generate-string
                            json/parse-string
                            pt/to-timestamp)
          facts-cmd {:command (command-names :replace-facts)
                     :version 3
                     :payload {:name certname
                               :environment "DEV"
                               :producer-timestamp producer-time
                               :values {"a" "1"
                                        "b" "2"
                                        "c" "3"}}}]
      (test-msg-handler facts-cmd publish discard-dir
        (is (= (query-to-vec
                "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname,
                          e.environment,
                          fs.producer_timestamp
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                     INNER JOIN environments as e on fs.environment_id = e.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
               [{:certname certname :name "a" :value "1" :producer_timestamp producer-time :environment "DEV"}
                {:certname certname :name "b" :value "2" :producer_timestamp producer-time :environment "DEV"}
                {:certname certname :name "c" :value "3" :producer_timestamp producer-time :environment "DEV"}]))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))
        (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
          (is (= result [(with-env {:certname certname})])))))))

(deftest replace-facts-with-v2-wire-format
  (with-test-db
    (let [certname  "foo.example.com"
          before-test-starts-time (-> 1 seconds ago)
          facts-cmd {:command (command-names :replace-facts)
                     :version 2
                     :payload {:name certname
                               :environment "DEV"
                               :values {"a" "1"
                                        "b" "2"
                                        "c" "3"}}}]
      (test-msg-handler facts-cmd publish discard-dir
        (is (= (query-to-vec
                "SELECT fp.path as name,
                          COALESCE(fv.value_string,
                                   cast(fv.value_integer as text),
                                   cast(fv.value_boolean as text),
                                   cast(fv.value_float as text),
                                   '') as value,
                          fs.certname,
                          e.environment
                   FROM factsets fs
                     INNER JOIN facts as f on fs.id = f.factset_id
                     INNER JOIN fact_values as fv on f.fact_value_id = fv.id
                     INNER JOIN fact_paths as fp on f.fact_path_id = fp.id
                     INNER JOIN environments as e on fs.environment_id = e.id
                   WHERE fp.depth = 0
                   ORDER BY name ASC")
               [{:certname certname :name "a" :value "1" :environment "DEV"}
                {:certname certname :name "b" :value "2" :environment "DEV"}
                {:certname certname :name "c" :value "3" :environment "DEV"}]))

        (is (every? (comp #(t/before? before-test-starts-time %)
                          to-date-time
                          :producer_timestamp)
                    (query-to-vec
                     "SELECT fs.producer_timestamp
                         FROM factsets fs")))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))
        (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
          (is (= result [(with-env {:certname certname})])))))))

(deftest replace-facts-bad-payload
  (let [bad-command {:command (command-names :replace-facts)
                     :version latest-facts-version
                     :payload "bad stuff"}]
    (dotestseq [version fact-versions
                :let [command bad-command]]
      (testing "should discard the message"
        (with-test-db
          (test-msg-handler command publish discard-dir
            (is (empty? (query-to-vec "SELECT * FROM facts")))
            (is (= 0 (times-called publish)))
            (is (seq (fs/list-dir discard-dir)))))))))

(deftest replace-facts-bad-payload-v2
  (let [bad-command {:command (command-names :replace-facts)
                     :version 2
                     :payload "bad stuff"}]
    (dotestseq [version fact-versions
                :let [command bad-command]]
      (testing "should discard the message"
        (with-test-db
          (test-msg-handler command publish discard-dir
            (is (empty? (query-to-vec "SELECT * FROM facts")))
            (is (= 0 (times-called publish)))
            (is (seq (fs/list-dir discard-dir)))))))))

(defn extract-error
  "Pulls the error from the publish var of a test-msg-handler"
  [publish]
  (-> publish
      args-supplied
      first
      second))

(deftest concurrent-fact-updates
  (testing "Should allow only one replace facts update for a given cert at a time"
    (with-test-db
      (let [certname "some_certname"
            facts {:certname certname
                   :environment "DEV"
                   :values {"domain" "mydomain.com"
                            "fqdn" "myhost.mydomain.com"
                            "hostname" "myhost"
                            "kernel" "Linux"
                            "operatingsystem" "Debian"
                            }
                   :producer_timestamp (to-timestamp (now))}
            command   {:command (command-names :replace-facts)
                       :version 4
                       :payload facts}

            hand-off-queue (java.util.concurrent.SynchronousQueue.)
            storage-replace-facts! scf-store/update-facts!]

        (jdbc/with-db-transaction []
          (scf-store/add-certname! certname)
          (scf-store/add-facts! {:certname certname
                                 :values (:values facts)
                                 :timestamp (-> 2 days ago)
                                 :environment nil
                                 :producer_timestamp (-> 2 days ago)
                                 :producer "bar.com"})
          (scf-store/ensure-environment "DEV"))

        (with-redefs [scf-store/update-facts!
                      (fn [fact-data]
                        (.put hand-off-queue "got the lock")
                        (.poll hand-off-queue 5 TimeUnit/SECONDS)
                        (storage-replace-facts! fact-data))]
          (let [first-message? (atom false)
                second-message? (atom false)
                fut (future
                      (test-msg-handler command publish discard-dir
                        (reset! first-message? true)))

                _ (.poll hand-off-queue 5 TimeUnit/SECONDS)

                new-facts (update-in facts [:values]
                                     (fn [values]
                                       (-> values
                                           (dissoc "kernel")
                                           (assoc "newfact2" "here"))))
                new-facts-cmd {:command (command-names :replace-facts)
                               :version 4
                               :payload new-facts}]

            (test-msg-handler new-facts-cmd publish discard-dir
              (reset! second-message? true)
              (let [[call :as calls] (args-supplied publish)
                    [msg] call
                    attempts (:attempts msg)]
                (is (= 1 (count calls)))
                (is (= 1 (count call)))
                (is (= 1 (count attempts)))
                (is (re-matches
                     #"(?sm).*ERROR: could not serialize access due to concurrent update.*"
                     (-> attempts first :exception full-sql-exception-msg)))))
            @fut
            (is (true? @first-message?))
            (is (true? @second-message?))))))))

(defn thread-id []
  (.getId (Thread/currentThread)))

(deftest fact-path-update-race
  ;; Simulates two update commands being processed for two different
  ;; machines at the same time.  Before we lifted fact paths into
  ;; facts, the race tested here could result in a constraint
  ;; violation when the two updates left behind an orphaned row.
  (let [certname-1 "some_certname1"
        certname-2 "some_certname2"
        producer-1 "some_producer1"
        producer-2 "some_producer2"
        ;; facts for server 1, has the same "mytimestamp" value as the
        ;; facts for server 2
        facts-1a {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1"}
                  :producer_timestamp (-> 2 days ago)
                  :producer producer-1}
        facts-2a {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1"}
                  :producer_timestamp (-> 2 days ago)
                  :producer producer-2}

        ;; same facts as before, but now certname-1 has a different
        ;; fact value for mytimestamp (this will force a new fact_value
        ;; that is only used for certname-1
        facts-1b {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1b"}
                  :producer_timestamp (-> 1 days ago)
                  :producer producer-1}

        ;; with this, certname-1 and certname-2 now have their own
        ;; fact_value for mytimestamp that is different from the
        ;; original mytimestamp that they originally shared
        facts-2b {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "2b"}
                  :producer_timestamp (-> 1 days ago)
                  :producer producer-2}

        ;; this fact set will disassociate mytimestamp from the facts
        ;; associated to certname-1, it will do the same thing for
        ;; certname-2
        facts-1c {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"}
                  :producer_timestamp (now)
                  :producer producer-1}
        facts-2c {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"}
                  :producer_timestamp (now)
                  :producer producer-2}
        command-1b   {:command (command-names :replace-facts)
                      :version 4
                      :payload facts-1b}
        command-2b   {:command (command-names :replace-facts)
                      :version 4
                      :payload facts-2b}
        command-1c   {:command (command-names :replace-facts)
                      :version 4
                      :payload facts-1c}
        command-2c   {:command (command-names :replace-facts)
                      :version 4
                      :payload facts-2c}

        ;; Wait for two threads to countdown before proceeding
        latch (java.util.concurrent.CountDownLatch. 2)

        ;; I'm modifying delete-pending-path-id-orphans! so that I can
        ;; coordinate access between the two threads, I'm storing the
        ;; reference to the original delete-pending-path-id-orphans!
        ;; here, so that I can delegate to it once I'm done
        ;; coordinating
        storage-delete-pending-path-id-orphans!
        scf-store/delete-pending-path-id-orphans!]

    (with-test-db
      (jdbc/with-db-transaction []
        (scf-store/add-certname! certname-1)
        (scf-store/add-certname! certname-2)
        (scf-store/add-facts! {:certname certname-1
                               :values (:values facts-1a)
                               :timestamp (now)
                               :environment nil
                               :producer_timestamp (:producer_timestamp facts-1a)
                               :producer producer-1})
        (scf-store/add-facts! {:certname certname-2
                               :values (:values facts-2a)
                               :timestamp (now)
                               :environment nil
                               :producer_timestamp (:producer_timestamp facts-2a)
                               :producer producer-2}))
      ;; At this point, there will be 4 fact_value rows, 1 for
      ;; mytimestamp, 1 for the operatingsystem, 2 for domain
      (with-redefs [scf-store/delete-pending-path-id-orphans!
                    (fn [& args]
                      ;; Once this has been called, it will countdown
                      ;; the latch and block
                      (.countDown latch)
                      ;; After the second command has been executed and
                      ;; it has decremented the latch, the await will no
                      ;; longer block and both threads will begin
                      ;; running again
                      (.await latch)
                      ;; Execute the normal delete-pending-path-id-orphans!
                      ;; function (unchanged)
                      (apply storage-delete-pending-path-id-orphans! args))]
        (let [first-message? (atom false)
              second-message? (atom false)
              fut-1 (future
                      (test-msg-handler command-1b publish discard-dir
                        (reset! first-message? true)))
              fut-2 (future
                      (test-msg-handler command-2b publish discard-dir
                        (reset! second-message? true)))]
          ;; The two commands are being submitted in future, ensure they
          ;; have both completed before proceeding
          @fut-2
          @fut-1
          ;; At this point there are 6 fact values, the original
          ;; mytimestamp, the two new mytimestamps, operating system and
          ;; the two domains
          (is (true? @first-message?))
          (is (true? @second-message?))
          ;; Submit another factset that does NOT include mytimestamp,
          ;; this disassociates certname-1's fact_value (which is 1b)
          (test-msg-handler command-1c publish discard-dir
            (reset! first-message? true))
          ;; Do the same thing with certname-2. Since the reference to 1b
          ;; and 2b has been removed, mytimestamp's path is no longer
          ;; connected to any fact values. The original mytimestamp value
          ;; of 1 is still in the table. It's now attempting to delete
          ;; that fact path, when the mytimestamp 1 value is still in
          ;; there.
          (test-msg-handler command-2c publish discard-dir
            (is (not (extract-error publish))))

          ;; Can we see the orphaned value '1', and does the global gc remove it.
          (is (= 1 (count
                    (query-to-vec
                     "select id from fact_values where value_string = '1'"))))
          (scf-store/garbage-collect! *db*)
          (is (zero?
               (count
                (query-to-vec
                 "select id from fact_values where value_string = '1'")))))))))

(deftest concurrent-catalog-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (with-test-db
      (let [test-catalog (get-in catalogs [:empty])
            {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
            nonwire-catalog (catalog/parse-catalog wire-catalog 6 (now))
            command {:command (command-names :replace-catalog)
                     :version 6
                     :payload wire-catalog}

            hand-off-queue (java.util.concurrent.SynchronousQueue.)
            storage-replace-catalog! scf-store/replace-catalog!]

        (jdbc/with-db-transaction []
          (scf-store/add-certname! certname)
          (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

        (with-redefs [scf-store/replace-catalog!
                      (fn [catalog timestamp]
                        (.put hand-off-queue "got the lock")
                        (.poll hand-off-queue 5 TimeUnit/SECONDS)
                        (storage-replace-catalog! catalog timestamp))]
          (let [first-message? (atom false)
                second-message? (atom false)
                fut (future
                      (test-msg-handler command publish discard-dir
                        (reset! first-message? true)))

                _ (.poll hand-off-queue 5 TimeUnit/SECONDS)

                new-wire-catalog (assoc-in wire-catalog [:edges]
                                           #{{:relationship "contains"
                                              :target       {:title "Settings" :type "Class"}
                                              :source       {:title "main" :type "Stage"}}})
                new-catalog-cmd {:command (command-names :replace-catalog)
                                 :version 6
                                 :payload new-wire-catalog}]

            (test-msg-handler new-catalog-cmd publish discard-dir
              (reset! second-message? true)
              (is (empty? (fs/list-dir discard-dir)))
              (let [[call :as calls] (args-supplied publish)
                    [msg] call
                    attempts (:attempts msg)]
                (is (= 1 (count calls)))
                (is (= 1 (count call)))
                (is (= 1 (count attempts)))
                (is (re-matches
                     #"(?sm).*ERROR: could not serialize access due to concurrent update.*"
                     (-> attempts first :exception full-sql-exception-msg)))))

            @fut
            (is (true? @first-message?))
            (is (true? @second-message?))))))))

(deftest concurrent-catalog-resource-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (with-test-db
      (let [test-catalog (get-in catalogs [:empty])
            {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
            nonwire-catalog (catalog/parse-catalog wire-catalog 6 (now))
            command {:command (command-names :replace-catalog)
                     :version 6
                     :payload wire-catalog}

            hand-off-queue (java.util.concurrent.SynchronousQueue.)
            storage-replace-catalog! scf-store/replace-catalog!]

        (jdbc/with-db-transaction []
          (scf-store/add-certname! certname)
          (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

        (with-redefs [scf-store/replace-catalog!
                      (fn [catalog timestamp]
                        (.put hand-off-queue "got the lock")
                        (.poll hand-off-queue 5 TimeUnit/SECONDS)
                        (storage-replace-catalog! catalog timestamp))]
          (let [first-message? (atom false)
                second-message? (atom false)
                fut (future
                      (test-msg-handler command publish discard-dir
                        (reset! first-message? true)))

                _ (.poll hand-off-queue 5 TimeUnit/SECONDS)

                new-wire-catalog (update wire-catalog :resources
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
                                 :version 6
                                 :payload new-wire-catalog}]

            (test-msg-handler new-catalog-cmd publish discard-dir
              (reset! second-message? true)
              (is (empty? (fs/list-dir discard-dir)))
              (let [[call :as calls] (args-supplied publish)
                    [msg] call
                    attempts (:attempts msg)]
                (is (= 1 (count calls)))
                (is (= 1 (count call)))
                (is (= 1 (count attempts)))
                (is (re-matches
                     #"(?sm).*ERROR: could not serialize access due to concurrent update.*"
                     (-> attempts first :exception full-sql-exception-msg)))))

            @fut
            (is (true? @first-message?))
            (is (true? @second-message?))))))))

(let [cases [{:certname "foo.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 3
                        :payload {:certname "foo.example.com"}}}
             {:certname "bar.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 3
                        :payload {:certname "bar.example.com"
                                  :producer_timestamp (now)}}}
             {:certname "bar.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 2
                        :payload (json/generate-string "bar.example.com")}}
             {:certname "bar.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 1
                        :payload (-> "bar.example.com"
                                     json/generate-string
                                     json/generate-string)}}]]

  (deftest deactivate-node-node-active
    (testing "should deactivate the node"
      (with-test-db
        (doseq [{:keys [certname command]} cases]
          (jdbc/insert! :certnames {:certname certname})
          (test-msg-handler command publish discard-dir
            (let [results (query-to-vec "SELECT certname,deactivated FROM certnames")
                  result  (first results)]
              (is (= (:certname result) certname))
              (is (instance? java.sql.Timestamp (:deactivated result)))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir)))
              (jdbc/do-prepared "delete from certnames")))))))

  (deftest deactivate-node-node-inactive
    (with-test-db
      (doseq [{:keys [certname command]} cases]
        (testing "should leave the node alone"
          (let [one-day   (* 24 60 60 1000)
                yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
                command (if (#{1 2} (:version command))
                          ;; Can't set the :producer_timestamp for the older
                          ;; versions (so that we can control the deactivation
                          ;; timestamp).
                          command
                          (assoc-in command
                                    [:payload :producer_timestamp] yesterday))]
            (jdbc/insert! :certnames
                          {:certname certname :deactivated yesterday})
            (test-msg-handler
              command
              publish discard-dir
              (let [[row & rest] (query-to-vec
                                  "SELECT certname,deactivated FROM certnames")]
                (is (empty? rest))
                (is (instance? java.sql.Timestamp (:deactivated row)))
                (if (#{1 2} (:version command))
                  (do
                    ;; Since we can't control the producer_timestamp.
                    (is (= certname (:certname row)))
                    (is (t/after? (from-sql-date (:deactivated row))
                                  (from-sql-date yesterday))))
                  (is (= {:certname certname :deactivated yesterday} row)))
                (is (= 0 (times-called publish)))
                (is (empty? (fs/list-dir discard-dir)))
                (jdbc/do-prepared "delete from certnames"))))))))

  (deftest deactivate-node-node-missing
    (testing "should add the node and deactivate it"
      (with-test-db
        (doseq [{:keys [certname command]} cases]
          (test-msg-handler command publish discard-dir
            (let [result (-> "SELECT certname, deactivated FROM certnames"
                             query-to-vec first)]
              (is (= (:certname result) certname))
              (is (instance? java.sql.Timestamp (:deactivated result)))
              (is (zero? (times-called publish)))
              (is (empty? (fs/list-dir discard-dir)))
              (jdbc/do-prepared "delete from certnames"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Report Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def v8-report
  (-> (:basic report-examples/reports)
      reports/report-query->wire-v8))

(def v7-report
  (-> v8-report
      (dissoc :producer :noop_pending)))

(def v6-report
  (-> v7-report
      (dissoc :catalog_uuid :cached_catalog_status :code_id)))

(def v5-report
  (-> (:basic report-examples/reports)
      reports/report-query->wire-v5))

(def v4-report
  (-> v5-report
      (dissoc :producer_timestamp :metrics :logs :noop)
      utils/underscore->dash-keys))

(def store-report-name (command-names :store-report))

(deftest store-v8-report-test
  (let [command {:command store-report-name
                 :version 8
                 :payload v8-report}]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(with-producer (select-keys v8-report [:certname]))]
               (-> (str "select certname, producer_id"
                        "  from reports")
                   query-to-vec)))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest store-v7-report-test
  (let [command {:command store-report-name
                 :version 7
                 :payload v7-report}]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(select-keys v7-report [:certname :catalog_uuid :cached_catalog_status :code_id])]
               (->> (str "select certname, catalog_uuid, cached_catalog_status, code_id"
                        "  from reports")
                   query-to-vec
                   (map (fn [row] (update row :catalog_uuid sutils/parse-db-uuid))))))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest store-v6-report-test
  (let [command {:command store-report-name
                 :version 6
                 :payload v6-report}]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(with-env (select-keys v6-report [:certname :configuration_version]))]
               (-> (str "select certname, configuration_version, environment_id"
                        "  from reports")
                   query-to-vec)))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest store-v5-report-test
  (let [command {:command store-report-name
                 :version 5
                 :payload v5-report}]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(with-env (select-keys v5-report [:certname
                                                  :configuration_version]))]
               (-> (str "select certname, configuration_version, environment_id"
                        "  from reports")
                   query-to-vec)))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest store-v4-report-test
  (let [command {:command store-report-name
                 :version 4
                 :payload v4-report}
        recent-time (-> 1 seconds ago)]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(with-env (utils/dash->underscore-keys
                           (select-keys v4-report
                                        [:certname :configuration-version])))]
               (-> (str "select certname, configuration_version, environment_id"
                        "  from reports")
                   query-to-vec)))

        ;; Status is present in v4+ (but not in v3)
        (is (= "unchanged" (-> (str "select rs.status from reports r"
                                    "  inner join report_statuses rs"
                                    "    on r.status_id = rs.id")
                               query-to-vec first :status)))

        ;; No producer_timestamp is included in v4, message received
        ;; time (now) is used intead
        (is (t/before? recent-time
                       (-> "select producer_timestamp from reports"
                           query-to-vec first :producer_timestamp to-date-time)))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(deftest store-v3-report-test
  (let [v3-report (dissoc v4-report :status)
        recent-time (-> 1 seconds ago)
        command {:command store-report-name
                 :version 3
                 :payload v3-report}]
    (with-test-db
      (test-msg-handler command publish discard-dir
        (is (= [(with-env (utils/dash->underscore-keys
                           (select-keys v3-report
                                        [:certname :configuration-version])))]
               (-> (str "select certname, configuration_version, environment_id"
                        "  from reports")
                   query-to-vec)))

        ;; No producer_timestamp is included in v4, message received
        ;; time (now) is used intead
        (is (t/before? recent-time
                       (-> "select producer_timestamp from reports"
                           query-to-vec
                           first
                           :producer_timestamp
                           to-date-time)))

        ;;Status is not supported in v3, should be nil
        (is (nil? (-> (query-to-vec "SELECT status_id FROM reports")
                      first :status)))
        (is (= 0 (times-called publish)))
        (is (empty? (fs/list-dir discard-dir)))))))

(defn- get-config []
  (conf/get-config (get-service svc-utils/*server* :DefaultedConfig)))

(deftest command-service-stats
  (svc-utils/with-puppetdb-instance
    (let [pdb (get-service svc-utils/*server* :PuppetDBServer)
          dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          enqueue-command (partial enqueue-command dispatcher)
          stats (partial stats dispatcher)
          real-replace! scf-store/replace-facts!]
      ;; Issue a single command and ensure the stats are right at each step.
      (is (= {:received-commands 0 :executed-commands 0} (stats)))
      (let [received-cmd? (promise)
            go-ahead-and-execute (promise)]
        (with-redefs [scf-store/replace-facts!
                      (fn [& args]
                        (deliver received-cmd? true)
                        @go-ahead-and-execute
                        (apply real-replace! args))]
          (enqueue-command (command-names :replace-facts)
                           4
                           "foo.local"
                           (tqueue/coerce-to-stream
                            {:environment "DEV" :certname "foo.local"
                             :values {:foo "foo"}
                             :producer_timestamp (to-string (now))}))
          @received-cmd?
          (is (= {:received-commands 1 :executed-commands 0} (stats)))
          (deliver go-ahead-and-execute true)
          (while (not= 1 (:executed-commands (stats)))
            (Thread/sleep 100))
          (is (= {:received-commands 1 :executed-commands 1} (stats))))))))

(deftest date-round-trip
  (svc-utils/with-puppetdb-instance
    (let [pdb (get-service svc-utils/*server* :PuppetDBServer)
          dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          enqueue-command (partial enqueue-command dispatcher)
          deactivate-ms 14250331086887
          ;; The problem only occurred if you passed a Date to
          ;; enqueue, a DateTime wasn't a problem.
          input-stamp (java.util.Date. deactivate-ms)
          expected-stamp (DateTime. deactivate-ms DateTimeZone/UTC)]
      (enqueue-command (command-names :deactivate-node)
                       3
                       "foo.local"
                       (tqueue/coerce-to-stream
                        {:certname "foo.local" :producer_timestamp input-stamp}))
      (is (svc-utils/wait-for-server-processing svc-utils/*server* 5000))
      ;; While we're here, check the value in the database too...
      (is (= expected-stamp
             (jdbc/with-transacted-connection
               (:scf-read-db (cli-svc/shared-globals pdb))
               :repeatable-read
               (from-sql-date (scf-store/node-deactivated-time "foo.local")))))
      (is (= expected-stamp
             (-> (client/get (str (utils/base-url->str svc-utils/*base-url*)
                                  "/nodes")
                             {:accept :json
                              :throw-exceptions true
                              :throw-entire-message true
                              :query-params {"query"
                                             (json/generate-string
                                              ["or" ["=" ["node" "active"] true]
                                               ["=" ["node" "active"] false]])}})
                 :body
                 json/parse-string
                 first
                 (get "deactivated")
                 (pt/from-string)))))))

(deftest command-response-channel
  (svc-utils/with-puppetdb-instance
    (let [pdb (get-service svc-utils/*server* :PuppetDBServer)
          dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
          enqueue-command (partial enqueue-command dispatcher)
          response-mult (response-mult dispatcher)
          response-chan (async/chan 4)
          producer-ts (java.util.Date.)]
      (async/tap response-mult response-chan)
      (enqueue-command (command-names :deactivate-node)
                       3
                       "foo.local"
                       (tqueue/coerce-to-stream
                        {:certname "foo.local" :producer_timestamp producer-ts}))

      (let [received-uuid (async/alt!! response-chan ([msg] (:producer-timestamp msg))
                                       (async/timeout 10000) ::timeout)]
        (is (= producer-ts))))))

(defn captured-ack-command [orig-ack-command results-atom]
  (fn [q command]
    (try
      (let [result (orig-ack-command q command)]
        (swap! results-atom conj result)
        result)
      (catch Exception e
        (swap! results-atom conj e)
        (throw e)))))

(deftest delete-old-catalog
  (with-test-db
    (svc-utils/call-with-puppetdb-instance
     (assoc (svc-utils/create-temp-config)
            :database *db*
            :command-processing {:threads 1})
     (fn []

       (let [message-listener (get-service svc-utils/*server* :MessageListenerService)
             dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
             enqueue-command (partial enqueue-command dispatcher)
             old-producer-ts (-> 2 days ago)
             new-producer-ts (now)
             base-cmd (get-in wire-catalogs [9 :basic])
             thread-count (conf/mq-thread-count (get-config))
             orig-ack-command queue/ack-command
             ack-results (atom [])
             semaphore (get-in (service-context message-listener)
                               [:consumer-threadpool :semaphore])
             cmd-1 (promise)
             cmd-2 (promise)
             cmd-3 (promise)]
         (with-redefs [queue/ack-command (captured-ack-command orig-ack-command ack-results)]
           (is (= thread-count (.drainPermits semaphore)))

           ;;This command is processed, but not used in the test, it's
           ;;purpose is to hold up the "shovel thread" waiting to grab
           ;;the semaphore permit and put the message on the
           ;;treadpool. By holding this up here we can put more
           ;;messages on the channel and know they won't be processed
           ;;until the semaphore permit is released and this first
           ;;message is put onto the threadpool
           (enqueue-command (command-names :replace-catalog)
                            9
                            "foo.com"
                            (->  base-cmd
                                 (assoc :producer_timestamp old-producer-ts
                                        :certname "foo.com")
                                 tqueue/coerce-to-stream)
                            #(deliver cmd-1 %))

           (enqueue-command (command-names :replace-catalog)
                            9
                            (:certname base-cmd)
                            (-> base-cmd
                                (assoc :producer_timestamp old-producer-ts)
                                tqueue/coerce-to-stream)
                            #(deliver cmd-2 %))

           (enqueue-command (command-names :replace-catalog)
                            9
                            (:certname base-cmd)
                            (-> base-cmd
                                (assoc :producer_timestamp new-producer-ts)
                                tqueue/coerce-to-stream)
                            #(deliver cmd-3 %))

           (.release semaphore)

           (is (not= ::timed-out (deref cmd-1 5000 ::timed-out)))
           (is (not= ::timed-out (deref cmd-2 5000 ::timed-out)))
           (is (not= ::timed-out (deref cmd-3 5000 ::timed-out)))

           ;; There's currently a lot of layering in the messaging
           ;; stack. The callback mechanism that delivers the promise
           ;; above occurs before the message is acknowledged. This
           ;; leads to a race condition. If your timing is off, you
           ;; could check the ack-results atom after the callback has
           ;; been invoked but before the message has been acknowledged.

           (loop [attempts 0]
             (when (and (not= 3 (count @ack-results))
                        (<= attempts 20))
               (Thread/sleep 100)
               (recur (inc attempts))))

           (is (= 3 (count @ack-results))
               "Waited up to 5 seconds for 3 acknowledgement results")

           (is (= [nil nil nil] @ack-results))))))))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (test-msg-handler (quote defun))
;;                              (dotestseq (quote defun)))
;; End:
