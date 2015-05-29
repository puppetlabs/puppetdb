(ns puppetlabs.puppetdb.command.core-test
  (:require [fs.core :as fs]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.catalogs :as catalog]
            [puppetlabs.puppetdb.examples.reports :as report-examples]
            [puppetlabs.puppetdb.scf.hash :as shash]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger]]
            [clj-time.format :as tfmt]
            [puppetlabs.puppetdb.command.core :refer :all]
            [puppetlabs.puppetdb.testutils :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [puppetlabs.puppetdb.jdbc :refer [query-to-vec]]
            [puppetlabs.puppetdb.examples :refer :all]
            [puppetlabs.puppetdb.testutils.reports :refer [munge-example-report-for-storage]]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [clj-time.coerce :refer [to-timestamp]]
            [clj-time.core :as t :refer [days ago now]]
            [clojure.test :refer :all]
            [clojure.tools.logging :refer [*logger-factory*]]
            [slingshot.slingshot :refer [throw+]]
            [puppetlabs.puppetdb.mq-listener :as mql]))

(use-fixtures :each with-test-db)

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

(defmacro test-msg-handler*
  [command publish-var discard-var opts-map & body]
  `(let [log-output#     (atom [])
         publish#        (call-counter)
         discard-dir#    (fs/temp-dir)
         handle-message# (mql/create-message-handler
                          publish# discard-dir# #(process-command! % ~opts-map))
         msg#            {:headers {:id "foo-id-1"
                                    :received (tfmt/unparse (tfmt/formatters :date-time) (now))}
                          :body (json/generate-string ~command)}]
     (try
       (binding [*logger-factory* (atom-logger log-output#)]
         (handle-message# msg#))
       (let [~publish-var publish#
             ~discard-var discard-dir#]
         ~@body
         ;; Uncommenting this line can be very useful for debugging
         ;; (println @log-output#)
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
                    attempt (first (get-in published [:annotations :attempts]))]
                (is (some->> attempt :error (re-find #"java.lang.Exception: non-fatal error")))
                (is (:trace attempt)))))))

      (testing "should be discarded if expired"
        (let [command (assoc-in command [:annotations :attempts] (repeat mql/maximum-allowable-retries {}))
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
                  mql/annotate-with-attempt (call-counter)]

      (testing "should log errors"
        (let [publish  (call-counter)]
          (testing "to DEBUG for initial retries"
            (let [log-output (atom [])]
              (binding [*logger-factory* (atom-logger log-output)]
                (mql/handle-command-retry (make-cmd 1) nil publish))

              (is (= (get-in @log-output [0 1]) :debug))))

          (testing "to ERROR for later retries"
            (let [log-output (atom [])]
              (binding [*logger-factory* (atom-logger log-output)]
                (mql/handle-command-retry (make-cmd mql/maximum-allowable-retries) nil publish))

              (is (= (get-in @log-output [0 1]) :error)))))))))

(deftest test-error-with-stacktrace
  (with-redefs [metrics.meters/mark!  (call-counter)]
    (let [publish  (call-counter)]
      (testing "Exception with stacktrace, no more retries"
        (let [log-output (atom [])]
          (binding [*logger-factory* (atom-logger log-output)]
            (mql/handle-command-retry (make-cmd 1) (RuntimeException. "foo") publish))
          (is (= (get-in @log-output [0 1]) :debug))
          (is (instance? Exception (get-in @log-output [0 2])))))

      (testing "Exception with stacktrace, no more retries"
        (let [log-output (atom [])]
          (binding [*logger-factory* (atom-logger log-output)]
            (mql/handle-command-retry (make-cmd mql/maximum-allowable-retries) (RuntimeException. "foo") publish))
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
  "Updates the `row-map` to include environment information."
  [row-map]
  (assoc row-map :environment_id (scf-store/environment-id "DEV")))

(defn version-num
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
  [:v6])

(deftest replace-catalog
  (doverseq [version catalog-versions
             :let [command {:command (command-names :replace-catalog)
                            :version 6
                            :payload (-> (get-in wire-catalogs [6 :empty])
                                         (assoc :producer_timestamp (now)))}]]
    (testing (str (command-names :replace-catalog) " " version)
      (let [certname (get-in command [:payload :certname])
            catalog-hash (shash/catalog-similarity-hash
                          (catalog/parse-catalog (:payload command) (version-num version)))
            command (stringify-payload command)
            one-day      (* 24 60 60 1000)
            yesterday    (to-timestamp (- (System/currentTimeMillis) one-day))
            tomorrow     (to-timestamp (+ (System/currentTimeMillis) one-day))]

        (testing "with no catalog should store the catalog"
          (with-fixtures
            (test-msg-handler command publish discard-dir
              (is (= [(with-env {:certname certname})]
                     (query-to-vec "SELECT certname, environment_id FROM catalogs")))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "with an existing catalog should replace the catalog"
          (with-fixtures
            (is (= (query-to-vec "SELECT certname FROM catalogs")
                   []))
            (sql/insert-record :certnames {:certname certname})
            (sql/insert-records :catalogs
                                {:hash (sutils/munge-hash-for-storage "00")
                                 :api_version 1
                                 :catalog_version "foo"
                                 :certname certname
                                 :producer_timestamp (to-timestamp (-> 1 days ago))})

            (test-msg-handler command publish discard-dir
              (is (= [(with-env {:certname certname :catalog catalog-hash})]
                     (query-to-vec (format "SELECT certname, %s as catalog, environment_id FROM catalogs"
                                           (sutils/sql-hash-as-str "hash")))))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "when replacing a catalog with a debug directory, should write out catalogs for inspection"
          (with-fixtures
            (sql/insert-record :certnames {:certname certname})

            (let [debug-dir (fs/absolute-path (temp-dir))]

              (sql/insert-records :catalogs
                                  {:hash (sutils/munge-hash-for-storage "0000")
                                   :api_version 1
                                   :catalog_version "foo"
                                   :certname certname
                                   :producer_timestamp (to-timestamp (t/minus (now) (-> 1 days)))})

              (is (nil? (fs/list-dir debug-dir)))
              (test-msg-handler-with-opts command publish discard-dir {:catalog-hash-debug-dir debug-dir}
                (is (= [(with-env {:certname certname :catalog catalog-hash})]
                       (query-to-vec (format "SELECT certname, %s as catalog, environment_id FROM catalogs"
                                             (sutils/sql-hash-as-str "hash")))))
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
            (sql/insert-record :certnames {:certname certname})
            (sql/insert-record :catalogs
                               {:id 1
                                :hash (sutils/munge-hash-for-storage "ab")
                                :api_version 1
                                :catalog_version "foo"
                                :certname certname
                                :timestamp tomorrow
                                :producer_timestamp (to-timestamp (now))})

            (test-msg-handler command publish discard-dir
              (is (= [{:certname certname :catalog "ab"}]
                     (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                           (sutils/sql-hash-as-str "hash")))))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))


        (testing "should reactivate the node if it was deactivated before the message"
          (with-fixtures
            (sql/insert-record :certnames {:certname certname :deactivated yesterday})
            (test-msg-handler command publish discard-dir
              (is (= [{:certname certname :deactivated nil}]
                     (query-to-vec "SELECT certname,deactivated FROM certnames")))
              (is (= [{:certname certname :catalog catalog-hash}]
                     (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                           (sutils/sql-hash-as-str "hash")))))
              (is (= 0 (times-called publish)))
              (is (empty? (fs/list-dir discard-dir))))))

        (testing "should store the catalog if the node was deactivated after the message"
          (scf-store/delete-certname! certname)
          (sql/insert-record :certnames {:certname certname :deactivated tomorrow})
          (test-msg-handler command publish discard-dir
            (is (= [{:certname certname :deactivated tomorrow}]
                   (query-to-vec "SELECT certname,deactivated FROM certnames")))
            (is (= [{:certname certname :catalog catalog-hash}]
                   (query-to-vec (format "SELECT certname, %s as catalog FROM catalogs"
                                         (sutils/sql-hash-as-str "hash")))))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))))))))

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
  (get-in wire-catalogs [6 :basic]))

(deftest catalog-with-updated-resource-line
  (doverseq [version catalog-versions
             :let [command {:command (command-names :replace-catalog)
                            :version 6
                            :payload basic-wire-catalog}
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
             :let [command {:command (command-names :replace-catalog)
                            :version 6
                            :payload basic-wire-catalog}
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
             :let [command {:command (command-names :replace-catalog)
                            :version 6
                            :payload basic-wire-catalog}
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
             :let [command {:command (command-names :replace-catalog)
                            :version 6
                            :payload basic-wire-catalog}
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
    (doverseq [version fact-versions
               :let [command v4-command]]

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
            (is (= result [(with-env {:certname certname})])))))))

  (deftest replace-facts-existing-facts
    (doverseq [version fact-versions
               :let [command v4-command]]

      (sql/transaction
       (scf-store/ensure-environment "DEV")
       (scf-store/add-certname! certname)
       (scf-store/replace-facts! {:certname certname
                                  :values {"x" "24" "y" "25" "z" "26"}
                                  :timestamp yesterday
                                  :producer_timestamp yesterday
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
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest replace-facts-newer-facts
    (doverseq [version fact-versions
               :let [command v4-command]]

      (sql/transaction
       (scf-store/ensure-environment "DEV")
       (scf-store/add-certname! certname)
       (scf-store/add-facts! {:certname certname
                              :values {"x" "24" "y" "25" "z" "26"}
                              :timestamp tomorrow
                              :producer_timestamp (to-timestamp (now))
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
          (is (empty? (fs/list-dir discard-dir)))))))

  (deftest replace-facts-deactivated-node-facts
    (doverseq [version fact-versions
               :let [command v4-command]]

      (testing "should reactivate the node if it was deactivated before the message"
        (sql/insert-record :certnames {:certname certname :deactivated yesterday})
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
        (sql/insert-record :certnames {:certname certname :deactivated tomorrow})
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
          (is (empty? (fs/list-dir discard-dir))))))))

(deftest replace-facts-bad-payload
  (let [bad-command {:command (command-names :replace-facts)
                     :version 4
                     :payload "bad stuff"}]
    (doverseq [version fact-versions
               :let [command bad-command]]
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

      (sql/transaction
       (scf-store/add-certname! certname)
       (scf-store/add-facts! {:certname certname
                              :values (:values facts)
                              :timestamp (-> 2 days ago)
                              :environment nil
                              :producer_timestamp (-> 2 days ago)}))

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
                             :version 4
                             :payload new-facts}]

          (test-msg-handler new-facts-cmd publish discard-dir
            (reset! second-message? true)
            (is (re-matches #".*BatchUpdateException.*(rollback|abort).*"
                            (extract-error-message publish))))
          @fut
          (is (true? @first-message?))
          (is (true? @second-message?)))))))

(defn thread-id []
  (.getId (Thread/currentThread)))

(deftest fact-path-update-race
  ;; Simulates two update commands being processed for two different
  ;; machines at the same time.  Before we lifted fact paths into
  ;; facts, the race tested here could result in a constraint
  ;; violation when the two updates left behind an orphaned row.
  (let [certname-1 "some_certname1"
        certname-2 "some_certname2"
        ;; facts for server 1, has the same "mytimestamp" value as the
        ;; facts for server 2
        facts-1a {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1"}
                  :producer_timestamp (-> 2 days ago)}
        facts-2a {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1"}
                  :producer_timestamp (-> 2 days ago)}

        ;; same facts as before, but now certname-1 has a different
        ;; fact value for mytimestamp (this will force a new fact_value
        ;; that is only used for certname-1
        facts-1b {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "1b"}
                  :producer_timestamp (-> 1 days ago)}

        ;; with this, certname-1 and certname-2 now have their own
        ;; fact_value for mytimestamp that is different from the
        ;; original mytimestamp that they originally shared
        facts-2b {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"
                           "mytimestamp" "2b"}
                  :producer_timestamp (-> 1 days ago)}

        ;; this fact set will disassociate mytimestamp from the facts
        ;; associated to certname-1, it will do the same thing for
        ;; certname-2
        facts-1c {:certname certname-1
                  :environment nil
                  :values {"domain" "mydomain1.com"
                           "operatingsystem" "Debian"}
                  :producer_timestamp (now)}
        facts-2c {:certname certname-2
                  :environment nil
                  :values {"domain" "mydomain2.com"
                           "operatingsystem" "Debian"}
                  :producer_timestamp (now)}
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

    (sql/transaction
     (scf-store/add-certname! certname-1)
     (scf-store/add-certname! certname-2)
     (scf-store/add-facts! {:certname certname-1
                            :values (:values facts-1a)
                            :timestamp (now)
                            :environment nil
                            :producer_timestamp (:producer_timestamp facts-1a)})
     (scf-store/add-facts! {:certname certname-2
                            :values (:values facts-2a)
                            :timestamp (now)
                            :environment nil
                            :producer_timestamp (:producer_timestamp facts-2a)}))

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
          (is (not (extract-error-message publish))))

        ;; Can we see the orphaned value '1', and does the global gc remove it.
        (is (= 1 (count
                  (query-to-vec
                   "select id from fact_values where value_string = '1'"))))
        (scf-store/garbage-collect! *db*)
        (is (zero?
             (count
              (query-to-vec
               "select id from fact_values where value_string = '1'"))))))))

(deftest concurrent-catalog-updates
  (testing "Should allow only one replace catalogs update for a given cert at a time"
    (let [test-catalog (get-in catalogs [:empty])
          {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
          nonwire-catalog (catalog/parse-catalog wire-catalog 6)
          command {:command (command-names :replace-catalog)
                   :version 6
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

              new-wire-catalog (assoc-in wire-catalog [:edges]
                                         #{{:relationship "contains"
                                            :target       {:title "Settings" :type "Class"}
                                            :source       {:title "main" :type "Stage"}}})
              new-catalog-cmd {:command (command-names :replace-catalog)
                               :version 6
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
          {certname :certname :as wire-catalog} (get-in wire-catalogs [6 :empty])
          nonwire-catalog (catalog/parse-catalog wire-catalog 6)
          command {:command (command-names :replace-catalog)
                   :version 6
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

              new-wire-catalog (update-in wire-catalog [:resources]
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
                               :payload (json/generate-string new-wire-catalog)}]

          (test-msg-handler new-catalog-cmd publish discard-dir
            (reset! second-message? true)
            (is (empty? (fs/list-dir discard-dir)))
            (is (re-matches #".*BatchUpdateException.*(rollback|abort).*"
                            (extract-error-message publish))))
          @fut
          (is (true? @first-message?))
          (is (true? @second-message?)))))))


(let [cases [{:certname "foo.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 3
                        :payload {:certname "foo.example.com"}}}
             {:certname "bar.example.com"
              :command {:command (command-names :deactivate-node)
                        :version 3
                        :payload {:certname "bar.example.com"
                                  :producer_timestamp (now)}}}]]

  (deftest deactivate-node-node-active
    (testing "should deactivate the node"
      (doseq [{:keys [certname command]} cases]
        (sql/insert-record :certnames {:certname certname})
        (test-msg-handler command publish discard-dir
          (let [results (query-to-vec "SELECT certname,deactivated FROM certnames")
                result  (first results)]
            (is (= (:certname result) certname))
            (is (instance? java.sql.Timestamp (:deactivated result)))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))
            (sql/do-prepared "delete from certnames"))))))

  (deftest deactivate-node-node-inactive
    (doseq [{:keys [certname command]} cases]
      (testing "should leave the node alone"
       (let [one-day   (* 24 60 60 1000)
             yesterday (to-timestamp (- (System/currentTimeMillis) one-day))]
         (sql/insert-record :certnames {:certname certname :deactivated yesterday})
         (test-msg-handler command publish discard-dir
           (is (= (query-to-vec "SELECT certname,deactivated FROM certnames")
                  [{:certname certname :deactivated yesterday}]))
           (is (= 0 (times-called publish)))
           (is (empty? (fs/list-dir discard-dir)))
           (sql/do-prepared "delete from certnames"))))))

  (deftest deactivate-node-node-missing
    (testing "should add the node and deactivate it"
      (doseq [{:keys [certname command]} cases]
        (test-msg-handler command publish discard-dir
          (let [results (query-to-vec "SELECT certname,deactivated FROM certnames")
                result  (first results)]
            (is (= (:certname result) certname ))
            (is (instance? java.sql.Timestamp (:deactivated result)))
            (is (= 0 (times-called publish)))
            (is (empty? (fs/list-dir discard-dir)))
            (sql/do-prepared "delete from certnames")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Report Command Tests
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def report-versions
  "Report versions supported. Version 1 is not currently being tested."
  [:v5])

(let [report (-> (:basic report-examples/reports)
                 (assoc :environment "DEV")
                 munge-example-report-for-storage)
      v5-command {:command (command-names :store-report)
                  :version 5
                  :payload report}]
  (deftest store-report
    (testing "should store the report"
      (doverseq [version report-versions
                 :let [command v5-command]]
        (test-msg-handler command publish discard-dir
          (is (= (query-to-vec "SELECT certname,configuration_version,environment_id FROM reports")
                 [(with-env {:certname (:certname report) :configuration_version (:configuration_version report)})]))
          (is (= 0 (times-called publish)))
          (is (empty? (fs/list-dir discard-dir))))))))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (test-msg-handler (quote defun))
;;                              (test-msg-handler-with-opts (quote defun))
;;                              (doverseq (quote defun))))
;; End:
