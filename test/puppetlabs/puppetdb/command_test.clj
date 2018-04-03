(ns puppetlabs.puppetdb.command-test
  (:require [me.raynes.fs :as fs]
            [clojure.java.jdbc :as sql]
            [metrics.counters :as counters]
            [metrics.meters :as meters]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants
             :refer [latest-catalog-version latest-facts-version]]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.metrics.core
             :refer [metrics-registries new-metrics]]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.catalogs :as catalog]
            [puppetlabs.puppetdb.examples.reports :refer [v4-report
                                                          v5-report
                                                          v6-report
                                                          v7-report
                                                          v8-report]]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.puppetdb.testutils.db :refer [*db* with-test-db]]
            [schema.core :as s]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger]]
            [clj-time.format :as tfmt]
            [puppetlabs.puppetdb.cli.services :as cli-svc]
            [puppetlabs.puppetdb.command :refer :all]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.testutils
             :refer [args-supplied call-counter default-timeout-ms dotestseq
                     times-called]]

            [puppetlabs.puppetdb.jdbc :refer [query-to-vec] :as jdbc]
            [puppetlabs.puppetdb.jdbc-test :refer [full-sql-exception-msg]]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.testutils.services :as svc-utils
             :refer [*server*]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [clj-time.coerce
             :refer [from-sql-date to-timestamp to-date-time to-string]]
            [clj-time.core :as t :refer [days ago now seconds]]
            [clojure.test :refer :all]
            [clojure.tools.logging :refer [*logger-factory*]]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.time :as pt]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.string :as str]
            [puppetlabs.stockpile.queue :as stock]
            [puppetlabs.puppetdb.nio :refer [get-path]]
            [puppetlabs.puppetdb.testutils.nio :as nio]
            [puppetlabs.puppetdb.testutils.queue :as tqueue
             :refer [catalog->command-req facts->command-req report->command-req deactivate->command-req]]
            [puppetlabs.puppetdb.queue :as queue]
            [puppetlabs.trapperkeeper.services
             :refer [service-context]]
            [overtone.at-at :refer [mk-pool scheduled-jobs]]
            [puppetlabs.puppetdb.testutils :as tu])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]
           [org.joda.time DateTime DateTimeZone]))

(defrecord CommandHandlerContext [message-handler command-chan dlo delay-pool response-chan q]
  java.io.Closeable
  (close [_]
    (async/close! command-chan)
    (async/close! response-chan)
    (fs/delete-dir (:path dlo))
    (#'overtone.at-at/shutdown-pool-now! @(:pool-atom delay-pool))))

(defn create-message-handler-context [q]
  (let [delay-pool (mk-pool)
        command-chan (async/chan 10)
        response-chan (async/chan 10)
        stats (atom {:received-commands 0
                     :executed-commands 0})
        dlo-dir (fs/temp-dir "test-msg-handler-dlo")
        dlo (dlo/initialize (.toPath dlo-dir)
                             (:registry (new-metrics "puppetlabs.puppetdb.dlo"
                                                     :jmx? false)))
        blacklisted-facts ["blacklisted-fact"]]
    (map->CommandHandlerContext
     {:handle-message (message-handler
                       q
                       command-chan
                       dlo
                       delay-pool
                       *db*
                       response-chan
                       stats
                       blacklisted-facts)
      :command-chan command-chan
      :dlo dlo
      :delay-pool delay-pool
      :response-chan response-chan
      :q q})))

(defmacro with-message-handler [binding-form & body]
  `(with-test-db
     (tqueue/with-stockpile q#
       (with-open [context# (create-message-handler-context q#)]
         (let [~binding-form context#]
           ~@body)))))

(defn add-fake-attempts [cmdref n]
  (loop [i 0
         result cmdref]
    (if (or (neg? n) (= i n))
      result
      (recur (inc i)
             (queue/cons-attempt result (Exception. (str "thud-" i)))))))

(defn task-count [delay-pool]
  (-> delay-pool
      :pool-atom
      deref
      :thread-pool
      .getQueue
      count))

(defn take-with-timeout!!
  "Takes from `port` via <!!, but will throw an exception if
  `timeout-in-ms` expires"
  [port timeout-in-ms]
  (async/alt!!
    (async/timeout timeout-in-ms)
    (throw (Exception. (format "Channel take timed out after '%s' ms" timeout-in-ms)))
    port
    ([v] v)))

(defn discard-count []
  (-> (meters/meter (get-in metrics-registries [:mq :registry])
                    ["global" "discarded"])
      meters/rates
      :total))

(defn failed-catalog-req [version certname payload]
  (queue/create-command-req "replace catalog" version certname nil "" identity
                            (tqueue/coerce-to-stream payload)))

(s/defn function-that-needs-int-via-schema [x :- s/Int]
  x)

(defn function-that-needs-int-via-precondition [x]
  {:pre [(integer? x)]}
  x)


(deftest command-processor-integration
  (let [v5-catalog (get-in wire-catalogs [5 :empty])]
    (testing "correctly formed messages"

      (testing "which are not yet expired"

        (testing "when successful should not raise errors or retry"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (handle-message (queue/store-command q (catalog->command-req 5 v5-catalog)))
            (is (= 0 (count (scheduled-jobs delay-pool))))
            (is (empty? (fs/list-dir (:path dlo))))))

        (testing "when a fatal error occurs should be discarded to the dead letter queue"
          (with-redefs [process-command-and-respond! (fn [& _] (throw+ (fatality (Exception. "fatal error"))))]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (let [discards (discard-count)]
                (handle-message (queue/store-command q (catalog->command-req 5 v5-catalog)))
                (is (= (inc discards) (discard-count))))
              (is (= 0 (count (scheduled-jobs delay-pool))))
              (is (= 2 (count (fs/list-dir (:path dlo))))))))

        (testing "when a schema error occurs should be discarded to the dead letter queue"
          (with-redefs [process-command-and-respond! (fn [& _] (upon-error-throw-fatality
                                                                (function-that-needs-int-via-schema "string")))]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (let [discards (discard-count)]
                (handle-message (queue/store-command q (catalog->command-req 5 v5-catalog)))
                (is (= (inc discards) (discard-count))))
              (is (= 0 (count (scheduled-jobs delay-pool))))
              (is (= 2 (count (fs/list-dir (:path dlo))))))))

        (testing "when a precondition error occurs should be discarded to the dead letter queue"
          (with-redefs [process-command-and-respond! (fn [& _] (upon-error-throw-fatality
                                                                (function-that-needs-int-via-precondition "string")))]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (let [discards (discard-count)]
                (handle-message (queue/store-command q (catalog->command-req 5 v5-catalog)))
                (is (= (inc discards) (discard-count))))
              (is (= 0 (count (scheduled-jobs delay-pool))))
              (is (= 2 (count (fs/list-dir (:path dlo))))))))

        (testing "when a non-fatal error occurs should be requeued with the error recorded"
          (let [expected-exception (Exception. "non-fatal error")]
            (with-redefs [process-command-and-respond! (fn [& _]
                                                         (throw+ expected-exception))
                          command-delay-ms 1
                          quick-retry-count 0]
              (with-message-handler {:keys [handle-message command-chan dlo delay-pool q]}
                (let [cmdref (queue/store-command q (catalog->command-req 5 v5-catalog))]

                  (is (= 0 (task-count delay-pool)))
                  (handle-message cmdref)

                  (is (empty? (fs/list-dir (:path dlo))))

                  (let [delayed-command (take-with-timeout!! command-chan default-timeout-ms)
                        actual-exception (:exception (first (:attempts delayed-command)))]
                    (are [x y] (= x y)
                      cmdref (dissoc delayed-command :attempts)
                      1 (count (:attempts delayed-command))
                      actual-exception expected-exception))))))))

      (testing "should be discarded if expired"
        (with-redefs [process-command-and-respond! (fn [& _] (throw (RuntimeException. "Expected failure")))]
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (let [cmdref (->> (get-in wire-catalogs [9 :empty])
                              (catalog->command-req 9)
                              (queue/store-command q))]
              (let [discards (discard-count)]
                (handle-message (add-fake-attempts cmdref maximum-allowable-retries))
                (is (= (inc discards) (discard-count))))
              (is (= 0 (task-count delay-pool)))
              (is (= 2 (count (fs/list-dir (:path dlo))))))))))

    (testing "should be discarded if incorrectly formed"
      (let [catalog-req (failed-catalog-req 5 (:name v5-catalog) "{\"malformed\": \"with no closing brace\"")
            process-counter (call-counter)]
        (with-redefs [process-command-and-respond! process-counter]
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (let [discards (discard-count)]
              (handle-message (queue/store-command q catalog-req))
              (is (= (inc discards) (discard-count))))
            (is (= 0 (task-count delay-pool)))
            (is (= 2 (count (fs/list-dir (:path dlo)))))
            (is (= 0 (times-called process-counter)))))))))

(deftest command-retry-handler
  (with-redefs [quick-retry-count 0]
    (let [process-message (fn [_] (throw (RuntimeException. "retry me")))]
      (testing "logs for each L2 failure up to the max"
        (doseq [i (range 0 maximum-allowable-retries)
                :let [log-output (atom [])]]
          (binding [*logger-factory* (atom-logger log-output)]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (is (= 0 (task-count delay-pool)))
              (handle-message (-> q
                                  (queue/store-command (failed-catalog-req 10 "cats" {:certname "cats"}))
                                  (add-fake-attempts i)))
              (is (= 1 (task-count delay-pool)))
              (is (= 0 (count (fs/list-dir (:path dlo)))))
              (is (= (get-in @log-output [0 1]) :error))
              (is (str/includes? (get-in @log-output [0 3]) "cats"))
              (is (instance? Exception (get-in @log-output [0 2])))
              (is (str/includes? (last (first @log-output))
                                 "Retrying after attempt"))))))

      (testing "a failed message after the max is discarded"
        (let [log-output (atom [])]
          (binding [*logger-factory* (atom-logger log-output)]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (handle-message (-> q
                                  (queue/store-command (failed-catalog-req 10 "cats" {:certname "cats"}))
                                  (add-fake-attempts maximum-allowable-retries)))
              (is (= 0 (task-count delay-pool)))
              (is (= 2 (count (fs/list-dir (:path dlo)))))
              (is (= (get-in @log-output [0 1]) :error))
              (is (instance? Exception (get-in @log-output [0 2])))
              (is (str/includes? (last (first @log-output))
                                 "Exceeded max"))
              (is (str/includes? (get-in @log-output [0 3]) "cats")))))))))

(deftest message-acknowledgement
  (testing "happy path, message acknowledgement when no failures occured"
    (tqueue/with-stockpile q
      (with-message-handler {:keys [handle-message dlo delay-pool q]}
        (let [cmdref (->> (get-in wire-catalogs [5 :empty])
                          (catalog->command-req 5)
                          (queue/store-command q))]
          (is (:payload (queue/cmdref->cmd q cmdref)))
          (handle-message cmdref)
          (is (not (queue/cmdref->cmd q cmdref)))
          (is (= 0 (task-count delay-pool)))
          (is (= 0 (count (fs/list-dir (:path dlo)))))))))

  (testing "Failures do not cause messages to be acknowledged"
    (tqueue/with-stockpile q
      (with-redefs [process-command-and-respond! (fn [& _] (throw+ (RuntimeException. "retry me")))]
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (let [entry (queue/store-command q (failed-catalog-req 10 "cats" {:certname "cats"}))]
            (is (:payload (queue/cmdref->cmd q entry)))
            (handle-message entry)
            (is (= 1 (task-count delay-pool)))
            (is (:payload (queue/cmdref->cmd q entry)))))))))

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
                  (catch fatal? e e))]
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
              :let [version-num (version-kwd->num version)
                    {:keys [certname] :as catalog} (-> wire-catalogs
                                                       (get-in [version-num :empty])
                                                       (assoc :producer_timestamp (now)))
                    make-cmd-req #(catalog->command-req version-num catalog)]]
    (testing (str (command-names :replace-catalog) " " version)
      (let [catalog-hash (shash/catalog-similarity-hash
                          (catalog/parse-catalog catalog version-num (now)))
            one-day      (* 24 60 60 1000)
            yesterday    (to-timestamp (- (System/currentTimeMillis) one-day))
            tomorrow     (to-timestamp (+ (System/currentTimeMillis) one-day))]

        (testing "with no catalog should store the catalog"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (handle-message (queue/store-command q (make-cmd-req)))
            (is (= [(with-env {:certname certname})]
                   (query-to-vec "SELECT certname, environment_id FROM catalogs")))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo))))))

        (testing "with optional ids should store the catalog"
          (let [job-id (when (> version-num 8) "1337")]
            (with-message-handler {:keys [handle-message dlo delay-pool q]}
              (handle-message
                (queue/store-command q (catalog->command-req
                                         version-num
                                         (cond-> (assoc catalog :code_id "my_git_sha1")
                                           job-id (assoc :job_id job-id)))))
              (is (= [(with-env {:certname certname :code_id "my_git_sha1" :job_id job-id})]
                     (query-to-vec "SELECT certname, code_id, job_id, environment_id FROM catalogs")))
              (is (= 0 (task-count delay-pool)))
              (is (empty? (fs/list-dir (:path dlo)))))))

        (testing "with an existing catalog should replace the catalog"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (is (= (query-to-vec "SELECT certname FROM catalogs")
                   []))
            (jdbc/insert! :certnames {:certname certname})
            (jdbc/insert! :catalogs {:hash (sutils/munge-hash-for-storage "00")
                                     :api_version 1
                                     :catalog_version "foo"
                                     :certname certname
                                     :producer_timestamp (to-timestamp (-> 1 days ago))})
            (handle-message (queue/store-command q (make-cmd-req)))
            (is (= [(with-env {:certname certname :catalog catalog-hash})]
                   (query-to-vec (format "SELECT certname, %s as catalog, environment_id FROM catalogs"
                                         (sutils/sql-hash-as-str "hash")))))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo))))))

        (testing "with a bad payload should discard the message"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (handle-message (queue/store-command q
                                                 (queue/create-command-req "replace catalog"
                                                                           version-num
                                                                           certname
                                                                           (ks/timestamp (now))
                                                                           ""
                                                                           identity
                                                                           (tqueue/coerce-to-stream "bad stuff"))))
            (is (empty? (query-to-vec "SELECT * FROM catalogs")))
            (is (= 0 (task-count delay-pool)))
            (is (seq (fs/list-dir (:path dlo))))))

        (testing "with a newer catalog should ignore the message"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (jdbc/insert! :certnames {:certname certname})
            (jdbc/insert! :catalogs {:hash (sutils/munge-hash-for-storage "ab")
                                     :api_version 1
                                     :catalog_version "foo"
                                     :certname certname
                                     :timestamp tomorrow
                                     :producer_timestamp (to-timestamp (t/plus (now) (days 1)))})
            (handle-message (queue/store-command q (make-cmd-req)))
            (is (= [{:certname certname :catalog "ab"}]
                   (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                         (sutils/sql-hash-as-str "hash")))))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo))))))

        (testing "should reactivate the node if it was deactivated before the message"
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (jdbc/insert! :certnames {:certname certname :deactivated yesterday})

            (handle-message (queue/store-command q (make-cmd-req)))

            (is (= [{:certname certname :deactivated nil}]
                   (query-to-vec "SELECT certname,deactivated FROM certnames")))
            (is (= [{:certname certname :catalog catalog-hash}]
                   (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                         (sutils/sql-hash-as-str "hash")))))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo)))))

          (testing "should store the catalog if the node was deactivated after the message"
            (with-message-handler {:keys [handle-message dlo delay-pool q]}

              (scf-store/delete-certname! certname)
              (jdbc/insert! :certnames {:certname certname :deactivated tomorrow})

              (handle-message (queue/store-command q (make-cmd-req)))

              (is (= [{:certname certname :deactivated tomorrow}]
                     (query-to-vec "SELECT certname,deactivated FROM certnames")))
              (is (= [{:certname certname :catalog catalog-hash}]
                     (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                           (sutils/sql-hash-as-str "hash")))))
              (is (= 0 (task-count delay-pool)))
              (is (empty? (fs/list-dir (:path dlo)))))))))))

;; If there are messages in the user's MQ when they upgrade, we could
;; potentially have commands of an unsupported format that need to be
;; processed. Although we don't support the catalog versions below, we
;; need to test that those commands will be processed properly
(deftest replace-catalog-with-v6
  (testing "catalog wireformat v6"
    (let [command {:command (command-names :replace-catalog)
                   :version 6
                   :payload (get-in wire-catalogs [6 :empty])}
          certname (get-in command [:payload :certname])
          cmd-producer-timestamp (get-in command [:payload :producer_timestamp])]

      (with-message-handler {:keys [handle-message dlo delay-pool q]}

        (handle-message (queue/store-command q (catalog->command-req 6 (get-in wire-catalogs [6 :empty]))))

        ;;names in v5 are hyphenated, this check ensures we're sending a v5 catalog
        (is (contains? (:payload command) :producer_timestamp))
        (is (= [(with-env {:certname certname})]
               (query-to-vec "SELECT certname, environment_id FROM catalogs")))
        (is (= 0 (task-count delay-pool)))
        (is (empty? (fs/list-dir (:path dlo))))

        ;;this should be the hyphenated producer timestamp provided above
        (is (= (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                   first
                   :producer_timestamp)
               (to-timestamp cmd-producer-timestamp)))))))

(deftest replace-catalog-with-v5
  (testing "catalog wireformat v5"
    (let [{certname :name
           producer-timestamp :producer-timestamp
           :as v5-catalog} (get-in wire-catalogs [5 :empty])]
      (with-message-handler {:keys [handle-message dlo delay-pool q]}

        (handle-message (queue/store-command q (catalog->command-req 5 v5-catalog)))

        ;;names in v5 are hyphenated, this check ensures we're sending a v5 catalog
        (is (contains? v5-catalog :producer-timestamp))
        (is (= [(with-env {:certname certname})]
               (query-to-vec "SELECT certname, environment_id FROM catalogs")))
        (is (= 0 (task-count delay-pool)))
        (is (empty? (fs/list-dir (:path dlo))))

        ;;this should be the hyphenated producer timestamp provided above
        (is (= (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                   first
                   :producer_timestamp)
               (to-timestamp producer-timestamp)))))))

(deftest replace-catalog-with-v4
  (let [{certname :name
         producer-timestmap :producer-timestamp :as v4-catalog} (get-in wire-catalogs [4 :empty])
        recent-time (-> 1 seconds ago)]
    (with-message-handler {:keys [handle-message dlo delay-pool q]}

      (handle-message (queue/store-command q (catalog->command-req 4 v4-catalog)))

      (is (false? (contains? v4-catalog :producer-timestamp)))
      (is (= [(with-env {:certname certname})]
             (query-to-vec "SELECT certname, environment_id FROM catalogs")))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))
      ;;v4 does not include a producer_timestmap, the backend
      ;;should use the time the command was received instead
      (is (t/before? recent-time
                     (-> (query-to-vec "SELECT producer_timestamp FROM catalogs")
                         first
                         :producer_timestamp
                         to-date-time))))))

(defn update-resource
  "Updated the resource in `catalog` with the given `type` and `title`.
   `update-fn` is a function that accecpts the resource map as an argument
   and returns a (possibly mutated) resource map."
  [catalog type title update-fn]
  (update catalog :resources
          (fn [resources]
            (mapv (fn [res]
                    (if (and (= (:title res) title)
                             (= (:type res) type))
                      (update-fn res)
                      res))
                  resources))))

(def basic-wire-catalog
  (get-in wire-catalogs [9 :basic]))

(deftest catalog-with-updated-resource-line
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (catalog->command-req 9 basic-wire-catalog)))

    (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                       (scf-store/latest-catalog-metadata
                                                        "basic.wire-catalogs.com")))]
      (is (= 10
             (get-in orig-resources [{:type "File" :title "/etc/foobar"} :line])))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))

      (handle-message (queue/store-command q (catalog->command-req 9
                                                                   (update-resource basic-wire-catalog "File" "/etc/foobar"
                                                                                    #(assoc % :line 20)))))

      (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :line] 20)
             (scf-store/catalog-resources (:certname_id
                                           (scf-store/latest-catalog-metadata
                                            "basic.wire-catalogs.com")))))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo)))))))

(deftest catalog-with-updated-resource-file
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (catalog->command-req 9 basic-wire-catalog)))


    (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                       (scf-store/latest-catalog-metadata
                                                        "basic.wire-catalogs.com")))]
      (is (= "/tmp/foo"
             (get-in orig-resources [{:type "File" :title "/etc/foobar"} :file])))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))

      (handle-message (queue/store-command q (catalog->command-req 9
                                                                   (update-resource basic-wire-catalog "File" "/etc/foobar"
                                                                                    #(assoc % :file "/tmp/not-foo")))))

      (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :file] "/tmp/not-foo")
             (scf-store/catalog-resources (:certname_id
                                           (scf-store/latest-catalog-metadata
                                            "basic.wire-catalogs.com")))))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo)))))))

(deftest catalog-with-updated-resource-exported
  (with-message-handler {:keys [handle-message dlo delay-pool q]}

    (handle-message (queue/store-command q (catalog->command-req 9 basic-wire-catalog)))

    (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                       (scf-store/latest-catalog-metadata
                                                        "basic.wire-catalogs.com")))]
      (is (= false
             (get-in orig-resources [{:type "File" :title "/etc/foobar"} :exported])))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))

      (handle-message (queue/store-command q (catalog->command-req 9
                                                                   (update-resource basic-wire-catalog "File" "/etc/foobar"
                                                                                    #(assoc % :exported true)))))
      (is (= (assoc-in orig-resources [{:type "File" :title "/etc/foobar"} :exported] true)
             (scf-store/catalog-resources (:certname_id
                                           (scf-store/latest-catalog-metadata
                                            "basic.wire-catalogs.com"))))))))

(deftest catalog-with-updated-resource-tags
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (catalog->command-req 9 basic-wire-catalog)))

    (let [orig-resources (scf-store/catalog-resources (:certname_id
                                                       (scf-store/latest-catalog-metadata
                                                        "basic.wire-catalogs.com")))]
      (is (= #{"file" "class" "foobar"}
             (get-in orig-resources [{:type "File" :title "/etc/foobar"} :tags])))
      (is (= 10
             (get-in orig-resources [{:type "File" :title "/etc/foobar"} :line])))
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))

      (handle-message (queue/store-command q (catalog->command-req 9
                                                                   (update-resource basic-wire-catalog "File" "/etc/foobar"
                                                                                    #(assoc %
                                                                                            :tags #{"file" "class" "foobar" "foo"}
                                                                                            :line 20)))))

      (is (= (-> orig-resources
                 (assoc-in [{:type "File" :title "/etc/foobar"} :tags]
                           #{"file" "class" "foobar" "foo"})
                 (assoc-in [{:type "File" :title "/etc/foobar"} :line] 20))
             (scf-store/catalog-resources (:certname_id
                                           (scf-store/latest-catalog-metadata
                                            "basic.wire-catalogs.com"))))))))

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
      one-day   (* 24 60 60 1000)
      yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
      tomorrow  (to-timestamp (+ (System/currentTimeMillis) one-day))]

  (deftest facts-package-deduplication
    (let [version :v5
          command (assoc facts
                         :producer "node0"
                         :package_inventory [["foo" "1.2.3" "apt"]
                                             ["bar" "2.3.4" "apt"]
                                             ["bar" "2.3.4" "apt"]
                                             ["baz" "3.4.5" "apt"]])]
      (testing "should deduplicate package_inventory on facts v5"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))
          (is (= [["bar" "2.3.4" "apt"]
                  ["baz" "3.4.5" "apt"]
                  ["foo" "1.2.3" "apt"]]
                 (rest
                  (jdbc/query
                   ["SELECT p.name as package_name, p.version, p.provider
                     FROM certname_packages cp
                          inner join packages p on cp.package_id = p.id
                          inner join certnames c on cp.certname_id = c.id
                     WHERE c.certname = ?
                     ORDER BY package_name, version, provider"
                    certname]
                   {:as-arrays? true}))))))))

  (deftest facts-blacklist
    (dotestseq [version fact-versions
                :let [command (update facts :values #(assoc % "blacklisted-fact" "val"))]]
      (testing "should ignore the blacklisted fact"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))
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
                  {:certname certname :name "c" :value "3"}]))))))

  (deftest replace-facts-no-facts
    (dotestseq [version fact-versions
                :let [command facts]]
      (testing "should store the facts"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))
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
          (is (= 0 (task-count delay-pool)))
          (is (empty? (fs/list-dir (:path dlo))))
          (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
            (is (= result [(with-env {:certname certname})])))))))

  (deftest replace-facts-existing-facts
    (dotestseq [version fact-versions
                :let [command facts]]
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
          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))
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
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo)))))))))

  (deftest replace-facts-newer-facts
    (dotestseq [version fact-versions
                :let [command facts]]
      (testing "should ignore the message"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (jdbc/with-db-transaction []
            (scf-store/ensure-environment "DEV")
            (scf-store/add-certname! certname)
            (scf-store/add-facts! {:certname certname
                                   :values {"x" "24" "y" "25" "z" "26"}
                                   :timestamp tomorrow
                                   :producer_timestamp (to-timestamp (now))
                                   :producer "bar.com"
                                   :environment "DEV"}))
          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))

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
          (is (= 0 (task-count delay-pool)))
          (is (empty? (fs/list-dir (:path dlo))))))))

  (deftest replace-facts-deactivated-node-facts
    (dotestseq [version fact-versions
                :let [command facts]]
      (testing "should reactivate the node if it was deactivated before the message"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}

          (jdbc/insert! :certnames {:certname certname :deactivated yesterday})

          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))
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
          (is (= 0 (task-count delay-pool)))
          (is (empty? (fs/list-dir (:path dlo))))))

      (testing "should store the facts if the node was deactivated after the message"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}

          (scf-store/delete-certname! certname)
          (jdbc/insert! :certnames {:certname certname :deactivated tomorrow})

          (handle-message (queue/store-command q (facts->command-req (version-kwd->num version) command)))

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
          (is (= 0 (task-count delay-pool)))
          (is (empty? (fs/list-dir (:path dlo)))))))))

;;v2 and v3 fact commands are only supported when commands are still
;;sitting in the queue from before upgrading
(deftest replace-facts-with-v3-wire-format
  (let [certname  "foo.example.com"
        producer-time (-> (now)
                          to-timestamp
                          json/generate-string
                          json/parse-string
                          pt/to-timestamp)
        facts-cmd {:name certname
                   :environment "DEV"
                   :producer-timestamp producer-time
                   :values {"a" "1"
                            "b" "2"
                            "c" "3"}}]
    (with-message-handler {:keys [handle-message dlo delay-pool q]}
      (handle-message (queue/store-command q (facts->command-req 3 facts-cmd)))

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
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))
      (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
        (is (= result [(with-env {:certname certname})]))))))

(deftest replace-facts-with-v2-wire-format
  (let [certname  "foo.example.com"
        before-test-starts-time (-> 1 seconds ago)
        facts-cmd {:name certname
                   :environment "DEV"
                   :values {"a" "1"
                            "b" "2"
                            "c" "3"}}]
    (with-message-handler {:keys [handle-message dlo delay-pool q]}

      (handle-message (queue/store-command q (facts->command-req 2 facts-cmd)))

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
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo))))
      (let [result (query-to-vec "SELECT certname,environment_id FROM factsets")]
        (is (= result [(with-env {:certname certname})]))))))

(deftest replace-facts-bad-payload
  (let [bad-command "bad stuff"]
    (dotestseq [version fact-versions
                :let [command bad-command]]
      (testing "should discard the message"
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (handle-message (queue/store-command q (queue/create-command-req "replace facts"
                                                                           latest-facts-version
                                                                           "foo.example.com"
                                                                           (ks/timestamp (now))
                                                                           ""
                                                                           identity
                                                                           (tqueue/coerce-to-stream "bad stuff"))))
          (is (empty? (query-to-vec "SELECT * FROM facts")))
          (is (= 0 (task-count delay-pool)))
          (is (seq (fs/list-dir (:path dlo)))))))))

(deftest replace-facts-bad-payload-v2
  (testing "should discard the message"
    (with-message-handler {:keys [handle-message dlo delay-pool q]}
      (handle-message (queue/store-command q (queue/create-command-req "replace facts"
                                                                       2
                                                                       "foo.example.com"
                                                                       (ks/timestamp (now))
                                                                       ""
                                                                       identity
                                                                       (tqueue/coerce-to-stream "bad stuff"))))
      (is (empty? (query-to-vec "SELECT * FROM facts")))
      (is (= 0 (task-count delay-pool)))
      (is (seq (fs/list-dir (:path dlo)))))))

(defn extract-error
  "Pulls the error from the publish var of a test-msg-handler"
  [publish]
  (-> publish
      args-supplied
      first
      second))

(defn pg-serialization-failure-ex? [ex]
  ;; Before pg 9.4, the message was in the first exception.  Now it's
  ;; in a second chained exception.  Look for both.
  (letfn [(failure? [candidate]
            (when candidate
              (when-let [m (.getMessage candidate)]
                (re-matches
                 #"(?sm).*ERROR: could not serialize access due to concurrent update.*"
                 m))))]
    (or (failure? (.getNextException ex))
        (failure? ex))))

(deftest concurrent-fact-updates
  (testing "Should allow only one replace facts update for a given cert at a time"
    (with-message-handler {:keys [handle-message dlo delay-pool q command-chan]}
      (let [certname "some_certname"
            facts {:certname certname
                   :environment "DEV"
                   :values {"domain" "mydomain.com"
                            "fqdn" "myhost.mydomain.com"
                            "hostname" "myhost"
                            "kernel" "Linux"
                            "operatingsystem" "Debian"}
                   :producer_timestamp (to-timestamp (now))}
            command   {:command (command-names :replace-facts)
                       :version 4
                       :payload facts}

            latch (java.util.concurrent.CountDownLatch. 2)
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

        (with-redefs [quick-retry-count 0
                      command-delay-ms 10000
                      scf-store/update-facts!
                      (fn [fact-data]
                        (.countDown latch)
                        (.await latch)
                        (storage-replace-facts! fact-data))]
          (let [first-message? (atom false)
                second-message? (atom false)
                fut (future
                      (handle-message (queue/store-command q (facts->command-req 4 facts)))
                      (reset! first-message? true))

                new-facts (update-in facts [:values]
                                     (fn [values]
                                       (-> values
                                           (dissoc "kernel")
                                           (assoc "newfact2" "here"))))
                new-facts-cmd {:command (command-names :replace-facts)
                               :version 4
                               :payload new-facts}]

            (handle-message (queue/store-command q (facts->command-req 4 new-facts)))
            (reset! second-message? true)

            @fut

            (let [failed-cmdref (take-with-timeout!! command-chan default-timeout-ms)]
              (is (= 1 (count (:attempts failed-cmdref))))
              (is (-> failed-cmdref :attempts first :exception
                      pg-serialization-failure-ex?)))

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

        ;; Wait for two threads to countdown before proceeding
        latch (java.util.concurrent.CountDownLatch. 2)

        ;; I'm modifying delete-pending-path-id-orphans! so that I can
        ;; coordinate access between the two threads, I'm storing the
        ;; reference to the original delete-pending-path-id-orphans!
        ;; here, so that I can delegate to it once I'm done
        ;; coordinating
        storage-delete-pending-path-id-orphans!
        scf-store/delete-pending-path-id-orphans!]

    (with-message-handler {:keys [handle-message dlo delay-pool q]}
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
                      (handle-message (queue/store-command q (facts->command-req 4 facts-1b)))
                      (reset! first-message? true))
              fut-2 (future
                      (handle-message (queue/store-command q (facts->command-req 4 facts-2b)))
                      (reset! second-message? true))]
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
          (handle-message (queue/store-command q (facts->command-req 4 facts-1c)))
          (reset! first-message? true)

          ;; Do the same thing with certname-2. Since the reference to 1b
          ;; and 2b has been removed, mytimestamp's path is no longer
          ;; connected to any fact values. The original mytimestamp value
          ;; of 1 is still in the table. It's now attempting to delete
          ;; that fact path, when the mytimestamp 1 value is still in
          ;; there.
          (handle-message (queue/store-command q (facts->command-req 4 facts-2c)))
          (is (= 0 (task-count delay-pool)))

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
    (with-message-handler {:keys [handle-message command-chan dlo delay-pool q]}
      (let [test-catalog (get-in catalogs [:empty])
            {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
            nonwire-catalog (catalog/parse-catalog wire-catalog 6 (now))
            latch (java.util.concurrent.CountDownLatch. 2)
            orig-replace-catalog! scf-store/replace-catalog!]

        (jdbc/with-db-transaction []
          (scf-store/add-certname! certname)
          (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

        (with-redefs [quick-retry-count 0
                      command-delay-ms 1
                      scf-store/replace-catalog!
                      (fn [& args]
                        (.countDown latch)
                        (.await latch)
                        (apply orig-replace-catalog! args))]
          (let [first-message? (atom false)
                second-message? (atom false)
                fut (future
                      (handle-message (queue/store-command q (catalog->command-req 4 wire-catalog)))
                      (reset! first-message? true))

                new-wire-catalog (assoc-in wire-catalog [:edges]
                                           #{{:relationship "contains"
                                              :target       {:title "Settings" :type "Class"}
                                              :source       {:title "main" :type "Stage"}}})]

            (handle-message (queue/store-command q (catalog->command-req 6 new-wire-catalog)))
            (reset! second-message? true)
            (is (empty? (fs/list-dir (:path dlo))))

            @fut

            (let [failed-cmdref (take-with-timeout!! command-chan default-timeout-ms)]
              (is (= 1 (count (:attempts failed-cmdref))))
              (is (-> failed-cmdref :attempts first :exception
                      pg-serialization-failure-ex?)))

            (is (true? @first-message?))
            (is (true? @second-message?))))))))

(deftest concurrent-catalog-resource-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (with-message-handler {:keys [handle-message command-chan dlo delay-pool q]}
      (let [test-catalog (get-in catalogs [:empty])
            {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
            nonwire-catalog (catalog/parse-catalog wire-catalog 6 (now))
            latch (java.util.concurrent.CountDownLatch. 2)
            orig-replace-catalog! scf-store/replace-catalog!]

        (jdbc/with-db-transaction []
          (scf-store/add-certname! certname)
          (scf-store/replace-catalog! nonwire-catalog (-> 2 days ago)))

        (with-redefs [quick-retry-count 0
                      command-delay-ms 1
                      scf-store/replace-catalog!
                      (fn [& args]
                        (.countDown latch)
                        (.await latch)
                        (apply orig-replace-catalog! args))]
          (let [fut (future
                      (handle-message (queue/store-command q (catalog->command-req 6 wire-catalog)))
                      ::handled-first-message)

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
                                                       :user   "root"}})]

            (handle-message (queue/store-command q (catalog->command-req 6 new-wire-catalog)))

            (is (= ::handled-first-message (deref fut tu/default-timeout-ms nil)))
            (is (empty? (fs/list-dir (:path dlo))))
            (let [failed-cmdref (take-with-timeout!! command-chan default-timeout-ms)]
              (is (= 1 (count (:attempts failed-cmdref))))
              (is (-> failed-cmdref :attempts first :exception
                      pg-serialization-failure-ex?)))))))))

(let [cases [[3 {:certname "foo.example.com"}]
             [3 {:certname "bar.example.com"
                 :producer_timestamp (now)}]
             [2 (json/generate-string "bar.example.com")]
             [1 (-> "bar.example.com"
                    json/generate-string
                    json/generate-string)]]]

  (deftest deactivate-node-node-active
    (testing "should deactivate the node"
      (doseq [[version command] cases
              :let [{:keys [certname] :as command-req} (deactivate->command-req version command)]]
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (jdbc/insert! :certnames {:certname certname})
          (handle-message (queue/store-command q command-req))
          (let [results (query-to-vec "SELECT certname,deactivated FROM certnames")
                result  (first results)]
            (is (= (:certname result) certname))
            (is (instance? java.sql.Timestamp (:deactivated result)))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo))))
            (jdbc/do-prepared "delete from certnames"))))))

  (deftest deactivate-node-node-inactive
    (doseq [[version orig-command] cases]
      (testing "should leave the node alone"
        (let [one-day   (* 24 60 60 1000)
              yesterday (to-timestamp (- (System/currentTimeMillis) one-day))
              command (if (#{1 2} version)
                        ;; Can't set the :producer_timestamp for the older
                        ;; versions (so that we can control the deactivation
                        ;; timestamp).
                        orig-command
                        (assoc orig-command :producer_timestamp yesterday))
              {:keys [certname] :as command-req} (deactivate->command-req version command)]

          (with-message-handler {:keys [handle-message dlo delay-pool q]}
            (jdbc/insert! :certnames
                          {:certname certname :deactivated yesterday})
            (handle-message (queue/store-command q command-req))

            (let [[row & rest] (query-to-vec
                                "SELECT certname,deactivated FROM certnames")]
              (is (empty? rest))
              (is (instance? java.sql.Timestamp (:deactivated row)))
              (if (#{1 2} version)
                (do
                  ;; Since we can't control the producer_timestamp.
                  (is (= certname (:certname row)))
                  (is (t/after? (from-sql-date (:deactivated row))
                                (from-sql-date yesterday))))
                (is (= {:certname certname :deactivated yesterday} row)))
              (is (= 0 (task-count delay-pool)))
              (is (empty? (fs/list-dir (:path dlo))))
              (jdbc/do-prepared "delete from certnames")))))))

  (deftest deactivate-node-node-missing
    (testing "should add the node and deactivate it"
      (doseq [[version command] cases
              :let [{:keys [certname] :as command-req} (deactivate->command-req version command)]]
        (with-message-handler {:keys [handle-message dlo delay-pool q]}
          (handle-message (queue/store-command q command-req))
          (let [result (-> "SELECT certname, deactivated FROM certnames"
                           query-to-vec first)]
            (is (= (:certname result) certname))
            (is (instance? java.sql.Timestamp (:deactivated result)))
            (is (= 0 (task-count delay-pool)))
            (is (empty? (fs/list-dir (:path dlo))))
            (jdbc/do-prepared "delete from certnames")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Report Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def store-report-name (command-names :store-report))

(deftest store-v8-report-test
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (report->command-req 8 v8-report)))
    (is (= [(with-producer (select-keys v8-report [:certname]))]
           (-> (str "select certname, producer_id"
                    "  from reports")
               query-to-vec)))
    (is (= 0 (task-count delay-pool)))
    (is (empty? (fs/list-dir (:path dlo))))))

(deftest store-v7-report-test
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (report->command-req 7 v7-report)))
    (is (= [(select-keys v7-report [:certname :catalog_uuid :cached_catalog_status :code_id])]
           (->> (str "select certname, catalog_uuid, cached_catalog_status, code_id"
                     "  from reports")
                query-to-vec
                (map (fn [row] (update row :catalog_uuid sutils/parse-db-uuid))))))
    (is (= 0 (task-count delay-pool)))
    (is (empty? (fs/list-dir (:path dlo))))))

(deftest store-v6-report-test
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (report->command-req 6 v6-report)))
    (is (= [(with-env (select-keys v6-report [:certname :configuration_version]))]
           (-> (str "select certname, configuration_version, environment_id"
                    "  from reports")
               query-to-vec)))
    (is (= 0 (task-count delay-pool)))
    (is (empty? (fs/list-dir (:path dlo))))))

(deftest store-v5-report-test
  (with-message-handler {:keys [handle-message dlo delay-pool q]}
    (handle-message (queue/store-command q (report->command-req 5 v5-report)))
    (is (= [(with-env (select-keys v5-report [:certname
                                              :configuration_version]))]
           (-> (str "select certname, configuration_version, environment_id"
                    "  from reports")
               query-to-vec)))
    (is (= 0 (task-count delay-pool)))
    (is (empty? (fs/list-dir (:path dlo))))))

(deftest store-v4-report-test
  (let [recent-time (-> 1 seconds ago)]
    (with-message-handler {:keys [handle-message dlo delay-pool q]}
      (handle-message (queue/store-command q (report->command-req 4 v4-report)))
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
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo)))))))

(deftest store-v3-report-test
  (let [v3-report (dissoc v4-report :status)
        recent-time (-> 1 seconds ago)]
    (with-message-handler {:keys [handle-message dlo delay-pool q]}
      (handle-message (queue/store-command q (report->command-req 3 v3-report)))
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
      (is (= 0 (task-count delay-pool)))
      (is (empty? (fs/list-dir (:path dlo)))))))

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
                           nil
                           (tqueue/coerce-to-stream
                            {:environment "DEV" :certname "foo.local"
                             :values {:foo "foo"}
                             :producer_timestamp (to-string (now))})
                           "")
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
                       nil
                       (tqueue/coerce-to-stream
                        {:certname "foo.local" :producer_timestamp input-stamp})
                       "")
      (is (svc-utils/wait-for-server-processing svc-utils/*server* default-timeout-ms)
          (format "Server didn't process received commands after %dms" default-timeout-ms))

      ;; While we're here, check the value in the database too...
      (is (= expected-stamp
             (jdbc/with-transacted-connection
               (:scf-read-db (cli-svc/shared-globals pdb))
               :repeatable-read
               (from-sql-date (scf-store/node-deactivated-time "foo.local")))))
      (is (= expected-stamp

             (-> (svc-utils/get (svc-utils/query-url-str "/nodes")
                                {:query-params
                                 {"query"
                                  (json/generate-string
                                   ["or" ["=" ["node" "active"] true]
                                    ["=" ["node" "active"] false]])}})
                 :body
                 first
                 :deactivated
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
                       nil
                       (tqueue/coerce-to-stream
                        {:certname "foo.local" :producer_timestamp producer-ts})
                       "")

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
       (let [dispatcher (get-service svc-utils/*server* :PuppetDBCommandDispatcher)
             enqueue-command (partial enqueue-command dispatcher)
             old-producer-ts (-> 2 days ago)
             new-producer-ts (now)
             base-cmd (get-in wire-catalogs [9 :basic])
             thread-count (conf/mq-thread-count (get-config))
             orig-ack-command queue/ack-command
             ack-results (atom [])
             semaphore (get-in (service-context dispatcher)
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
                            nil
                            (->  base-cmd
                                 (assoc :producer_timestamp old-producer-ts
                                        :certname "foo.com")
                                 tqueue/coerce-to-stream)
                            ""
                            #(deliver cmd-1 %))

           (enqueue-command (command-names :replace-catalog)
                            9
                            (:certname base-cmd)
                            nil
                            (-> base-cmd
                                (assoc :producer_timestamp old-producer-ts)
                                tqueue/coerce-to-stream)
                            ""
                            #(deliver cmd-2 %))

           (enqueue-command (command-names :replace-catalog)
                            9
                            (:certname base-cmd)
                            nil
                            (-> base-cmd
                                (assoc :producer_timestamp new-producer-ts)
                                tqueue/coerce-to-stream)
                            ""
                            #(deliver cmd-3 %))

           (.release semaphore)

           (is (not= ::timed-out (deref cmd-1 tu/default-timeout-ms ::timed-out)))
           (is (not= ::timed-out (deref cmd-2 tu/default-timeout-ms ::timed-out)))
           (is (not= ::timed-out (deref cmd-3 tu/default-timeout-ms ::timed-out)))

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

(deftest missing-queue-files-accounted-for-in-stats
  ;; Use a lock to hold up command processing while we trash the
  ;; queue.
  (let [cmd-gate (Object.)
        orig-handler message-handler]
    (with-redefs [message-handler (fn [& wrap-args]
                                    (let [h (apply orig-handler wrap-args)]
                                      (fn [& handle-args]
                                        (locking cmd-gate
                                          (apply h handle-args)))))]
      (svc-utils/with-puppetdb-instance
        (let [dispatcher (get-service *server* :PuppetDBCommandDispatcher)
              enqueue-command (partial enqueue-command dispatcher)
              stats #(stats dispatcher)
              deactivate (fn [name]
                           (enqueue-command (command-names :deactivate-node)
                                            3 name nil
                                            (tqueue/coerce-to-stream
                                             {:certname name
                                              :producer_timestamp (now)})
                                            ""))
              depths #(vector (counters/value (global-metric :depth))
                              (some-> (cmd-metric "deactivate node" 3 :depth)
                                      counters/value))
              seen-rates #(vector (meters/rates (global-metric :seen))
                                  (some-> (cmd-metric "deactivate node" 3 :seen)
                                          meters/rates))
              qdir (get-path (conf/stockpile-dir (get-config)) "cmd" "q")
              start-stats (stats)
              start-depths (depths)
              start-seen (seen-rates)]
          ;; Enqueue a few commands
          (locking cmd-gate
            (deactivate "x.local")
            (deactivate "y.local")
            (deactivate "z.local")
            (doseq [i (range (/ default-timeout-ms 20))
                    :while (not= 3 (- (:received-commands (stats))
                                      (:received-commands start-stats)))]
              (Thread/sleep 20))
            (is (= (map #(+ (or % 0) 3) start-depths)
                   (depths)))
            ;; Delete their files
            (is (= 3 (count (for [p (-> (Files/newDirectoryStream qdir)
                                        .iterator
                                        iterator-seq)]
                              (do (Files/delete p) p))))))
          ;; Lock has been released, wait for queue to drain
          (doseq [i (range (/ default-timeout-ms 20))
                  :while (not= [0 0] (depths))]
            (Thread/sleep 20))
          (is (= [0 0] (depths)))
          (let [[global cmd] (seen-rates)
                [start-global start-cmd] start-seen]
            ;; Can't check for a non-zero rate too because if you mark
            ;; fast enough, initially (at least), the rates can be zero.
            (is (= (+ 3 (:total start-global)) (:total global)))
            (is (= (+ 3 (or (:total start-cmd) 0)) (:total cmd)))))))))
