(ns puppetlabs.pe-puppetdb-extensions.sync.convergence-test
  (:require [clj-time.core :as t]
            [clojure.core.match :as ccm]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.impl :as impl]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.pe-puppetdb-extensions.sync.core :refer [sync-from-remote!]]
            [puppetlabs.pe-puppetdb-extensions.sync.sync-test-utils
             :refer [start-sync]]
            [puppetlabs.pe-puppetdb-extensions.testutils
             :refer [sync-config with-ext-instances]]
            [puppetlabs.puppetdb.cli.services :as cli-svcs]
            [puppetlabs.puppetdb.client :refer [submit-command-via-http!]]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.examples.reports :as report-examples]
            [puppetlabs.puppetdb.command :as dispatch]
            [puppetlabs.puppetdb.http.command :as command]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.scf.storage :as scf-storage]
            [puppetlabs.puppetdb.utils :refer [base-url->str]]
            [puppetlabs.trapperkeeper.app :refer [get-service]]
            [puppetlabs.pe-puppetdb-extensions.sync.services :as services]
            [puppetlabs.pe-puppetdb-extensions.sync.bucketed-summary :as bucketed-summary]
            [puppetlabs.http.client.sync :as http-sync])
  (:import
   [org.joda.time Period DateTime]))

(def ^:private command-sequence-size 11)
(def ^:private example-certname "foo.local")
(def ^:private example-resource-uuid (kitchensink/uuid))

(defn- make-test-catalog [stamp n]
  (-> (get-in examples/wire-catalogs [dispatch/latest-catalog-version :basic])
      (assoc :certname example-certname
             :producer_timestamp (DateTime. stamp)
             :transaction_uuid (kitchensink/uuid))
      (update :resources conj
              {:type "File"
               :title example-resource-uuid
               :exported false
               :file example-resource-uuid
               :line n
               :tags ["file" "class" "foobar"]
               :parameters {:ensure "directory"
                            :group  "root"
                            :user   "root"}})))

(defn- make-test-report [stamp n]
  (-> report-examples/reports
      :basic
      (assoc-in [:resource_events :data 0 :line] n)
      (assoc :producer_timestamp (DateTime. stamp)
             :transaction_uuid (kitchensink/uuid))
      reports/report-query->wire-v8))

(defn- make-test-facts [stamp n]
  {:certname example-certname
   :environment "DEV"
   :values (assoc tuf/base-facts "operatingsystem" (str "datamangler/" n))
   :producer_timestamp (DateTime. stamp)
   :producer "mom.com"})

(def ^:private startup-time (t/now))

(def ^:private in-the-past-day
  (gen/choose (.getMillis (-> 1 t/days t/ago))
              (.getMillis startup-time)))

(def ^:private in-the-next-day
  (gen/choose (.getMillis startup-time)
              (.getMillis (-> 1 t/days t/from-now))))

(def ^:private around-startup
  (let [m (.getMillis startup-time)]
    (gen/choose (- m command-sequence-size) (+ m command-sequence-size))))

(def gen-test-timestamp
  "Returns a generator producing timestamps that fall sometime in the
  past day, sometime in the next day, or within the 5 miliseconds
  around the test start time."
  (gen/one-of [in-the-past-day
               around-startup
               in-the-next-day]))

;;; These generate the vector representation of the test commands.
;;; They are eventually interpreted and executed by
;;; exec-convergence-cmd.

(defn- simple-pdb-cmd-gen [cmd]
  (gen/hash-map :cmd (gen/return cmd)
                :target (gen/elements [:x :y])))

(def ^:private gen-flush-queue (simple-pdb-cmd-gen :flush-queue))

(def ^:private gen-sync (simple-pdb-cmd-gen :sync-to))

(def ^:private gen-replace-catalog
  (gen/hash-map :cmd (gen/return :replace-catalog)
                :target (gen/elements [:x :y])
                :stamp gen-test-timestamp
                :seed (gen/choose 0 9)))

(def ^:private gen-replace-facts
  (gen/hash-map :cmd (gen/return :replace-facts)
                :target (gen/elements [:x :y])
                :stamp gen-test-timestamp
                :seed (gen/choose 0 9)))

(def ^:private gen-store-report
  (gen/hash-map :cmd (gen/return :store-report)
                :target (gen/elements [:x :y])
                :stamp gen-test-timestamp
                :seed (gen/choose 0 9)))

(def ^:private gen-deactivate-node
  (gen/hash-map :cmd (gen/return :deactivate-node)
                :target (gen/elements [:x :y])
                :stamp gen-test-timestamp))

(def ^:private gen-sleep
  (gen/hash-map :cmd (gen/return :sleep)
                :ms (gen/choose 1 200)))

(def ^:private gen-convergence-cmd
  (gen/one-of
   [gen-replace-catalog
    gen-replace-facts
    gen-store-report
    gen-deactivate-node
    gen-flush-queue
    gen-sync
    gen-sleep]))

(defn- cmd-stats [pdb]
  (dispatch/stats (get-service (:server pdb) :PuppetDBCommandDispatcher)))

(defn- wait-for-processing [pdb]
  (loop [n 0]
    (let [{:keys [received-commands executed-commands]} (cmd-stats pdb)]
      (when (not= executed-commands received-commands)
        (when (= n 30000)
          (throw (-> "command processing not finished after 30s (%d/%d)"
                     (format executed-commands received-commands)
                     Exception.)))
        (Thread/sleep 10)
        (recur (+ n 10))))))

(defn- exec-convergence-cmd [pdb-x pdb-y command]
  (letfn [(order-targets [x y target]
            (if (= target :x) [pdb-x pdb-y] [pdb-y pdb-x]))
          (submit [x y target command version data]
            (let [[x y] (order-targets x y target)]
              (submit-command-via-http! (:command-url x) (:certname data)
                                        command version data)))]
    (ccm/match command
      {:cmd :replace-catalog :target target :stamp stamp :seed n}
      (submit pdb-x pdb-y target "replace catalog"
              dispatch/latest-catalog-version (make-test-catalog stamp n))

      {:cmd :replace-facts :target target :stamp stamp :seed n}
      (submit pdb-x pdb-y target "replace facts"
              dispatch/latest-facts-version (make-test-facts stamp n))

      {:cmd :store-report :target target :stamp stamp :seed n}
      (submit pdb-x pdb-y target "store report"
              dispatch/latest-report-version (make-test-report stamp n))

      {:cmd :deactivate-node :target target :stamp stamp}
      (submit pdb-x pdb-y target "deactivate node" 3 {:certname example-certname
                                                      :producer_timestamp stamp})

      {:cmd :flush-queue :target target}
      (wait-for-processing (first (order-targets pdb-x pdb-y target)))

      {:cmd :sync-to :target target}
      (let [[x y] (order-targets pdb-x pdb-y target)]
        (start-sync :from y :to x))

      {:cmd :sleep :ms n}
      (Thread/sleep n)

      :else (throw (IllegalArgumentException.
                    (format "Unrecognized sync test command: %s" command))))))

(defn- sync-directly! [pdb remote-url]
  (with-open [http-client (http-sync/create-client {})]
    (let [server (:server pdb)
          pdb-service (get-service server :PuppetDBServer)
          sync-service (get-service server :PuppetDBSync)
          dispatcher (get-service server :PuppetDBCommandDispatcher)
          globals (cli-svcs/shared-globals pdb-service)
          scf-read-db (:scf-read-db globals)]
      (sync-from-remote! (partial cli-svcs/query pdb-service)
                         (partial services/bucketed-summary-query sync-service)
                         (partial dispatch/enqueue-command dispatcher)
                         {:url remote-url :client http-client}
                         Period/ZERO
                         identity))))

(defn- count-possible-deactivation-races
  [commands]
  ;; Rationale: we believe there is still at least one window for
  ;; false negatives, where these tests may fail with a non-zero
  ;; transferred count due to a possible race during node
  ;; deactivation.  We think this could happen because both
  ;; set-local-deactivation-status! and the commands/process-command!
  ;; method for deactivate node both test to see whether or not
  ;; deactivation is appropriate.
  ;;
  ;; Imagine the set-local-deactivation! test is positive, and so sync
  ;; issues (transfers) the deactivation command, but before it can
  ;; execute, a replace factset command for the relevant certname
  ;; (that has a newer producer_timestamp) runs on the remote.  After
  ;; that, when the remote deactivate node command fires, it will be
  ;; ignored by process-command!, and the next time sync runs the
  ;; deactivation might be selected for transfer again (if the host
  ;; executing sync hasn't received the replace factset command, or
  ;; any other that invalidates the deactivation).
  (let [deactivations (map :stamp
                           (filter #(= :deactivate-node (:cmd %)) commands))
        reactivations (map :stamp
                           (filter #(#{:replace-catalog
                                       :replace-facts
                                       :store-report}
                                     (:cmd %))
                                   commands))]
    (count (filter (fn [deactivation] (some #(>= % deactivation) reactivations))
                   deactivations))))

(defn- check-sync [dir pdb remote commands]
  ;; FIXME: for now the capturing and dumping of sync log events on
  ;; error is disabled below because it also ends up dumping the
  ;; instance logs every time.  Fixing that will require reworking
  ;; pdb-instance log handling and the muting function, because the
  ;; log config is global.
  (let [events (atom [])
        result (do ;; svcs/with-log-level :sync :debug
                    (do ;; svcs/with-logging-to-atom :sync events
                        (sync-directly! pdb remote)))
        max-expected-transfers (count-possible-deactivation-races commands)]
    (is (= #{:transferred :failed} (set (keys result))))
    (is (zero? (:failed result)))
    ;; ideally, there would be zero transfers here because the two pdbs have
    ;; converged. But we must account for the possibility of deactivation races.
    (is (>= max-expected-transfers (:transferred result)))
    (if (or (not (= #{:transferred :failed} (set (keys result))))
            (not (zero? (:failed result)))
            (not (>= max-expected-transfers (:transferred result))))
      (binding [*out* *err*]
        (println (format "Failed sync %s:" (name dir)))
        (clojure.pprint/pprint commands)
        (println (format "Max expected transfers: %d  Actual: %d"
                         max-expected-transfers
                         (:transferred result)))
        ;;(println "Log:")
        ;;(clojure.pprint/pprint @events)
        false)
      true)))

(def ^:private gen-test-options
  "Test options that can be overriden by environment variables."
  {:num-tests
   (if-let [n (System/getenv "PDB_CONVERGENCE_TEST_COUNT")]
     (Integer/parseInt n)
     3)
   :seed
   (if-let [n (System/getenv "GEN_SEED")]
     (Long/parseLong n)
     ;; Here we use the internal method for generating the seed that test.check uses, so we
     ;; can print it out ourselves early, to catch deadlocked tests and such.
     (impl/get-current-time-millis))})

(def ^:private convergence-trials-run (atom 0))

(defn- run-convergence-test [commands]
  (with-ext-instances [pdb1 (sync-config nil) pdb2 (sync-config nil)]
    (let [pdb1-url (base-url->str (:server-url pdb1))
          pdb2-url (base-url->str (:server-url pdb2))]
      (swap! convergence-trials-run inc)
      (binding [*out* *err*]
        (print (format "Trial %d/%d\r"
                       @convergence-trials-run (:num-tests gen-test-options)))
        (flush))
      (do ;; svcs/with-log-level :sync :debug
        (doseq [cmd commands]
          (exec-convergence-cmd pdb1 pdb2 cmd))
        (wait-for-processing pdb1)
        (wait-for-processing pdb2)
        ;; Verify sync - all commands should have been executed
        ;;(semlog/logp [:sync :info] "===== All commands should be finished")
        (sync-directly! pdb1 pdb2-url)
        (wait-for-processing pdb1)
        ;;(semlog/logp [:sync :info] "===== PDB1 synced")
        (sync-directly! pdb2 pdb1-url)
        (wait-for-processing pdb2)
        ;;(semlog/logp [:sync :info] "===== PDB2 synced")
        (let [s1 (check-sync :to-x pdb1 pdb2-url commands)
              s2 (check-sync :to-y pdb2 pdb1-url commands)]
          (and s1 s2))))))

(defn duplicate-reports-omitted? [commands]
  "Indicates whether or not the same report is stored more than once."
  (->> commands
       (filter #(= :store-report (:cmd %)))
       (map #(dissoc % :stamp))
       frequencies
       vals
       (every? #(< % 2))))

(defspec convergence gen-test-options
  ;; Given the cycle time and the number of possible test
  ;; sets (particularly given the current timestamp arrangement),
  ;; shrinking is disabled for now.
  (do
    (print "test.check configuration: ")
    (clojure.pprint/pprint gen-test-options)
    (prop/for-all [commands (->> (gen/vector gen-convergence-cmd
                                             command-sequence-size)
                                 (gen/such-that duplicate-reports-omitted?)
                                gen/no-shrink)]
                  (run-convergence-test commands))))
