(ns puppetlabs.puppetdb.test-in-parallel
  "Supports \"lein test-in-parallel --concurrency N [NAMESPACE ...] [SELECTOR ...]\".
  By default, divides all the test namespaces (listed on the command
  line, or all in test-paths, into N roughly equal (count-wise)
  partitions and tests each partition in parallel in its own JVM via
  \"lein test PARTITION [SELECTOR ...]\".

  This can be useful for crudely parallelizing a test suite that isn't
  safe to parallelize within a single JVM, perhaps due to reliance on
  with-redefs, etc.

  Generally, you'll want to run a --calibration first (and at least
  once), which will collect test timing information in
  target/pdb-parallel-testing.edn and use it to partition (balance)
  the test namespaces more effectively.

  It should be possible to collect the timing information from each
  jvm during a concurrent run, and make the calibration more
  automatic."
  (:require
   [bultitude.core :refer [namespaces-on-classpath]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.test :as test :refer [*test-out* report]]
   [clojure.tools.cli :refer [parse-opts]])
  (:import
   (java.io FileNotFoundException)
   (java.lang ProcessBuilder ProcessBuilder$Redirect)
   (java.nio.file Path)))

;; Related to lein test selectors (which require eval):
;; https://github.com/cognitect-labs/test-runner include/exclude tags

(defn logn [& args] (binding [*out* *err*] (apply println args)))
(defn logf [& args] (binding [*out* *err*] (print (apply format args)) (flush)))

(defn as-file [x] (if (instance? Path x) (.toFile x) (io/as-file x)))

(def options
  [[nil "--test-paths PATH_LIST" "lein project.clj :test-paths as an EDN collection"
    :parse-fn #(distinct (edn/read-string %))
    :validate [#(every? string? %) "must be an EDN collection of strings"]]
   [nil "--state FILE" "persistent state (target/pdb-parallel-testing.edn)"
    :default "target/pdb-parallel-testing.edn"
    :default-desc ""] ;; too long for help
   ["-n" "--concurrency N" "number of batches to run in parallel (1)"
    :default 1 :default-desc ""
    :parse-fn #(Long/parseLong %) :validate [pos? "must be positive"]]
   [nil "--calibrate" :desc "collect test timing for future scheduling"]
   ;; Because "lein test-in-parallel --help" shows generic info
   [nil "--test-in-parallel-help"]])

(defn lein-test-ns-syms
  "Given leiningen project :test-paths, returns the same collection of
  test namespaces (as symbols) that \"lein test\" would select."
  [test-paths]
  (sort (namespaces-on-classpath :classpath (map io/file test-paths)
                                 :ignore-unreadable? false)))

(defn create-batch-test-process [batch cmd]
  (let [adjust-env #(doto ^java.util.Map (.environment %)
                      (.remove "CLASSPATH")
                      (.put "PDB_TEST_ID" (str batch)))]
    (doto (ProcessBuilder. cmd)
      (.redirectOutput ProcessBuilder$Redirect/INHERIT)
      (.redirectError ProcessBuilder$Redirect/INHERIT)
      adjust-env)))

(defn partition-tests
  "Partitions the namespaces into n partitions while attempting to
  minimize the longest expected test time of any partition.  Currently
  intends to be LPT-ish:
  https://en.wikipedia.org/wiki/Longest-processing-time-first_scheduling"
  [n namespaces expected-times]
  (assert (pos? n))
  (assert (every? symbol? namespaces))
  (assert (or (nil? expected-times) (map? expected-times)))
  (doseq [[k v] expected-times] (assert (symbol? k)) (assert (number? v)))
  (let [avg-time (if (seq expected-times)
                   (/ (apply + (vals expected-times))
                      (count expected-times))
                   1)
        ns-and-times (->> (mapv #(vector %1 (get expected-times %1 avg-time)) namespaces)
                          (sort #(compare (second %2) (second %1))))]
    (loop [result (mapv #(vector % 0 []) (range n))
           [[ns time] & nsts] ns-and-times]
      (if-not ns
        (filterv seq (map #(nth % 2) result))
        (let [[i total part-nsts] (apply min-key second result)]
          (recur (update result i #(assoc %
                                          1 (+ total time)
                                          2 (conj part-nsts ns)))
                 nsts))))))

(defn run-concurrent-test-batches [namespaces selectors n expected-ns-times]
  (assert (every? symbol? namespaces))
  (assert (every? keyword? selectors))
  (let [batches (partition-tests n namespaces expected-ns-times)
        cmds (map (fn [namespaces]
                    (-> (into ["lein" "trampoline" "test"] (map name namespaces))
                        (into (map str selectors))))
                  batches)

        procs (mapv (fn [i cmd]
                      (logf "[%d] Starting %s\n" i (pr-str cmd))
                      (.start (create-batch-test-process i cmd)))
                    (range) cmds)
        statuses (mapv #(.waitFor %) procs)]
    (if (every? zero? statuses)
      0
      (do
        (doseq [[cmd status] (map vector cmds statuses)
                :when (not= 0 status)]
          (apply logn "ERROR: exit" status "for" cmd))
        2))))

(defn read-state-file [path]
  (try
    (with-open [rdr (io/reader (as-file path))
                rdr (java.io.PushbackReader. rdr)]
      (edn/read rdr))
    (catch FileNotFoundException _)))

(defn pdb-test-nss [dirs]
  ;; The integration tests launch their own vms...
  (remove #(str/starts-with? % "puppetlabs.puppetdb.integration")
          (lein-test-ns-syms dirs)))

(defn test-in-parallel
  [dirs namespaces & {:keys [concurrency selectors state]}]
  ;; dirs only matter if no namespaces are given
  ;; FIXME: cleanup on C-c and other parent crashes.
  (let [namespaces (or (seq namespaces) (pdb-test-nss dirs))]
    (binding [*out* *err*]
      (if (empty? selectors)
        (println "Testing: ")
        (println (str "Testing (selection " (str/join " " selectors) "):")))
      (doseq [n namespaces]
        (println " " n)))
    (run-concurrent-test-batches namespaces selectors concurrency
                                 (:time state))))

(def test-times (atom {}))

(def calibration-methods-initialized? (atom false))

(defn initialize-calibration-methods []
  ;; The begin/end defaults, e.g. via (get-method
  ;; report :begin-test-ns) before these defmethods, just print or do
  ;; nothing, so we don't want/need to call them.
  (locking initialize-calibration-methods
    (when-not @calibration-methods-initialized?
      (defmethod report :begin-test-ns [{:keys [ns]}]
        (binding [*out* *test-out*] (println "\nTesting" (ns-name ns)))
        (swap! test-times assoc-in [(ns-name ns) :start] (System/nanoTime)))
      (defmethod report :end-test-ns [{:keys [ns]}]
        (let [end (System/nanoTime)
              nn (ns-name ns)
              start (get-in @test-times [nn :start])
              elapsed (- end start)]
          (swap! test-times update nn #(merge % {:end end :elapsed elapsed}))
          (binding [*out* *test-out*]
            (-> "Tested %s in %.3fs\n"
                (format (ns-name ns) (/ elapsed 1000000000.0))
                print))))
      (reset! calibration-methods-initialized? true))))

(defn calibrate [dirs namespaces state-file]
  ;; Currently ignores selectors - see
  ;; leiningen.test/form-for-suppressing-unselected-tests for the
  ;; details.
  (let [namespaces (map symbol (or (seq namespaces) (pdb-test-nss dirs)))]
    (initialize-calibration-methods)
    (apply require namespaces)
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (if-not (zero? (+ fail error))
        2
        (let [info (reduce-kv (fn select-timing [result ns {:keys [elapsed]}]
                                (assoc result ns elapsed))
                              {}
                              @test-times)]
          (with-open [out (io/writer state-file)]
            (pprint {:time info} out)
            0))))))

(defn read-selector [x]
  (let [v (edn/read-string x)]
    (when-not (keyword? v)
      ;; FIXME: friendlier error handling
      (throw (ex-info (str "Expected test selector, found " (pr-str x)) {:val x})))
    v))

(defn read-ns-sym [x]
  (let [v (edn/read-string x)]
    (when-not (symbol? v)
      ;; FIXME: friendlier error handling
      (throw (ex-info (str "Expected namespace symbol, found " (pr-str x)) {:val x})))
    v))

(defn validate-nss-and-selectors [args]
  (let [parts (partition-by #(str/starts-with? % ":") args)]
    (case (count parts)
      0 nil
      1 (if (str/starts-with? (ffirst parts) ":")
          [nil (->> parts first (mapv read-selector))]
          [(->> parts first (mapv read-ns-sym)) nil])
      2 (let [[namespaces selectors] parts]
          (if (str/starts-with? (ffirst namespaces) ":")
            (do
              (logn "error: test selectors must come after namespaces")
              2)
            [(mapv read-ns-sym namespaces) (mapv read-selector selectors)]))
      (do
        (logn "error: all test selectors must come after namespaces")
        2))))

(defn main [& args]
  (let [{:keys [arguments errors options summary]} (parse-opts args options)]
    (System/exit
     (cond
       errors (do (logn (str/join \newline errors)) 2)

       (:test-in-parallel-help options)
       (do
         (println "Usage: lein test-in-parallel OPT...")
         (println summary)
         0)

       (:calibrate options)
       (if-not (= 1 (:concurrency options))
         (do
           (logn "error: --calibration requires a ---concurrency of 1")
           2)
         (let [ns-sel (validate-nss-and-selectors arguments)]
           (if (integer? ns-sel)
             ns-sel
             (let [[namespaces selectors] ns-sel]
               (if (seq selectors)
                 (do
                   (logn "error: --calibration doesn't currently allow or respect selectors")
                   2)
                 (calibrate (:test-paths options) namespaces (:state options)))))))

       :else
       (let [ns-sel (validate-nss-and-selectors arguments)]
         (if (integer? ns-sel)
           ns-sel
           (let [[namespaces selectors] ns-sel]
             (test-in-parallel (:test-paths options) namespaces
                               (assoc (select-keys options [:concurrency])
                                      :selectors selectors
                                      :state (-> options :state read-state-file))))))))))
