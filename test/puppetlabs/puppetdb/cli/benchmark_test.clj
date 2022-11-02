(ns puppetlabs.puppetdb.cli.benchmark-test
  (:require [clojure.pprint]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.cli.benchmark :as benchmark]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.lint :refer [ignore-value]]
            [puppetlabs.puppetdb.nio :refer [copts copt-replace get-path]]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.cli :refer [get-nodes example-catalog
                                                       example-report example-facts
                                                       example-certname]]
            [puppetlabs.puppetdb.utils :as utils :refer [with-captured-throw]]
            [puppetlabs.kitchensink.core :as ks])
  (:import
   [clojure.lang ExceptionInfo]
   [java.nio.file Files]))

(defn mock-submit-record-fn [submitted-records entity]
  (fn [base-url _certname version payload-string]
    (swap! submitted-records conj
           {:entity entity
            :base-url base-url
            :version version
            :payload-string payload-string
            :payload (keywordize-keys payload-string)})))

(defn call-with-benchmark-status
  [config cli-args f]
  (let [submitted-records (atom [])]
    (with-redefs [client/submit-catalog (mock-submit-record-fn submitted-records
                                                               :catalog)
                  client/submit-report (mock-submit-record-fn submitted-records
                                                              :report)
                  client/submit-facts (mock-submit-record-fn submitted-records
                                                             :factset)
                  config/load-config (fn [_] config)
                  ;; This normally calls System/exit on a cli error;
                  ;; we'd rather have the exception.
                  utils/try-process-cli (fn [body] (body))
                  benchmark/benchmark-shutdown-timeout tu/default-timeout-ms]
      (f submitted-records (benchmark/benchmark-wrapper cli-args)))))

(defn benchmark-nummsgs
  [config & cli-args]
  ;; Assumes cli-args does not indicate a --runinterval) run
  (call-with-benchmark-status config cli-args
                              (fn [submitted {:keys [join]}]
                                (is (= true (join tu/default-timeout-ms)))
                                @submitted)))

(deftest config-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {}))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Missing required argument '--config'")))))

(deftest numhosts-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {} "--config" "anything.ini"))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Missing required argument '--numhosts'")))))

(deftest nummsgs-or-runinterval-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {}
                                                  "--config" "anything.ini"
                                                  "--numhosts" "42"))]
    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Error: Either -N/--nummsgs or -i/--runinterval is required.")))))

(deftest runs-with-runinterval
  (call-with-benchmark-status
   {}
   ["--config" "anything.ini" "--numhosts" "333" "--runinterval" "1"]
   (fn [submitted {:keys [stop]}]
     (let [enough-records (* 3 42)
           finished (promise)
           watch-key (Object.)
           watcher (fn [_k _ref _old new]
                     (when (>= (count new) enough-records)
                       (deliver finished true)))]
       (add-watch submitted watch-key watcher)
       (when-not (>= (count @submitted) enough-records) ; avoid add-watch race
         (deref finished tu/default-timeout-ms nil))
       (is (>= (count @submitted) enough-records))
       (stop)))))

(deftest multiple-messages-and-hosts
  (let [numhosts 2
        nummsgs 3
        submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" (str numhosts)
                                     "--nummsgs" (str nummsgs))]
    (is (= (* numhosts nummsgs 3) (count submitted)))))

(deftest archive-flag-works
  (let [export-out-file (.getPath (tu/temp-file "benchmark-test" ".tar.gz"))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (is (empty? (get-nodes)))

       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace catalog" 8 example-catalog)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "store report" 7 example-report)
       (svc-utils/sync-command-post (svc-utils/pdb-cmd-url) example-certname "replace facts" 4 example-facts)

       (let [r (svc-utils/get (svc-utils/admin-url-str "/archive")
                              {:as :stream :decompress-body false})]
         (if-not (= 200 (:status r))
           (do
             (binding [*out* *err*]
               (println "Unable to retrieve pdb archive:")
               (clojure.pprint/pprint r))
             (is (= 200 (:status r))))
           (do
             (ignore-value
              (Files/copy ^java.io.InputStream (:body r)
                          (get-path export-out-file)
                          (copts [copt-replace])))
             (let [numhosts 2
                   nummsgs 3
                   submitted (benchmark-nummsgs {}
                                                "--config" "anything.ini"
                                                "--numhosts" (str numhosts)
                                                "--nummsgs" (str nummsgs)
                                                "--archive" export-out-file)]
               (is (= (* numhosts nummsgs 3) (count submitted)))))))))))

(deftest consecutive-reports-are-distinct
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10")
        reports (->> submitted
                     (filter #(= :report (:entity %)))
                     (map :payload))]
    (is (= 10 (->> reports (map :configuration_version) distinct count)))
    (is (= 10 (->> reports (map :start_time) distinct count)))
    (is (= 10 (->> reports (map :end_time) distinct count)))))

(deftest randomize-catalogs-and-factsets
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "1"
                                     "--nummsgs" "10"
                                     "--rand-perc" "100")
        catalog-hashes (->> submitted
                            (filter #(= :catalog (:entity %)))
                            (map :payload)
                            (map hash))
        factset-hashes (->> submitted
                            (filter #(= :factset (:entity %)))
                            (map :payload)
                            (map hash))]
    (is (= 10 (count (distinct catalog-hashes))))
    (is (= 10 (count (distinct factset-hashes))))))

(deftest all-hosts-are-present
  (let [submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "100"
                                     "--nummsgs" "3")
        catalogs-per-host (->> submitted
                               (filter #(= :catalog (:entity %)))
                               (map :payload)
                               (group-by :certname)
                               (#(update-vals % count)))]
    (is (= 100 (count catalogs-per-host)))
    ; We should see at least 2 catalogs for every host accounting for potential
    ; jitter in the simulation timer.
    (is (every? #(>= % 2) (vals catalogs-per-host)))))
