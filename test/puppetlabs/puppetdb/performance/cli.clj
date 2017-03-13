(ns puppetlabs.puppetdb.performance.cli
  (:require [me.raynes.fs :as fs]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as s]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.data.xml :as xml]
            [clojure.tools.namespace.find :as nsf]
            [clojure.java.classpath :as cp]
            [clojure.string :as s]
            [puppetlabs.puppetdb.utils :refer [println-err]]))

;; thanks, StackOverflow
(defn- find-by-var-meta [metadata-flag]
  (->> (all-ns)
       (mapcat ns-publics)
       (keep
        (fn [[_ v]]
          (when (-> v meta metadata-flag)
            v)))))

(defn list-tests []
  ;; require all namespaces that might have perf tests in them
  (->> (nsf/find-namespaces (cp/classpath))
       (filter #(s/starts-with? % "puppetlabs.puppetdb.performance"))
       (map require)
       doall)
  (->> (find-by-var-meta :performance)
       (map meta)
       (map (fn [{:keys [name ns]}]
              (str ns "/" name)))))

(defn run-test [var-name]
  (let [[ns-name _] (s/split var-name #"\/")]
    (require (symbol ns-name)))
  (-> (symbol var-name)
      find-var
      (apply [])
      (assoc :name var-name)))

(defn resolve-ref [ref]
  (s/trim (:out (sh "git" "rev-parse" ref))))

(defn git-export [ref work-tree]
  (sh "sh" "-c" (str "git archive --format=tar --prefix=" work-tree "/ " ref " | tar xf -")))

(defn run-all-tests-in [dir]
  (let [results-file (str dir "/perf-result.edn")]
    (if (fs/exists? results-file)
      (do
        (println-err "Reading cached results from" results-file)
        (edn/read-string (slurp results-file)))
      (let [test-list-str (:out (sh "lein" "perf-test" "list" :dir dir))
            tests (edn/read-string test-list-str)
            _ (println-err "Running" (count tests) "tests in" dir)
            results (mapv (fn [test-name]
                            (println-err " - " test-name)
                            (let [test-out (:out (sh "lein" "perf-test" "run" test-name
                                                     :dir dir))]
                              (edn/read-string {:default (fn [tag x] x)} test-out)))
                          tests)]
        (spit results-file (pr-str results))
        results))))

(defn- outer-join [xrel yrel ks]
  (let [keyed-x (set/index xrel ks)
        keyed-y (set/index yrel ks)
        all-keys (set/union (keys keyed-x) (keys keyed-y))]
    (->> all-keys
         (map (fn [k]
                (let [xrow (first (get keyed-x k))
                      yrow (first (get keyed-y k))]
                  (merge xrow yrow))))
         (into #{}))))

(defn check-combined-test-result [{{:keys [regress-percent improve-percent] :as new} :new
                                   old :old
                                   :as test}]
  ;; we write down ms, as is typical in clojure, but criterium gives us seconds
  (let [old-time (:mean old)
        new-time (:mean new)
        regress-threshold (+ old-time (* (/ regress-percent 100) old-time))
        improve-threshold (- old-time (* (/ improve-percent 100) old-time))]
    (assoc test :errors
           (remove nil?
                   [(when (>= new-time regress-threshold)
                      (str "Mean time of " new-time
                           " is more than " regress-percent
                           " percent slower than" old-time))
                    (when (<= new-time improve-threshold)
                      (str "Mean time of " new-time
                           " is more than " improve-percent
                           " percent faster than " old-time))]))))

(defn regress [old-ref new-ref]
  (let [old-sha (resolve-ref old-ref)
        new-sha (some-> new-ref resolve-ref)
        old-dir (str "perf-regress-temp/old-" old-sha)
        new-dir (if new-ref
                  (str "perf-regress-temp/new-" new-sha)
                  ".")]
    (fs/mkdir "perf-regress-temp")
    (fs/mkdir old-dir)
    (fs/mkdir new-dir)

    (git-export old-sha old-dir)
    (when new-sha
      (git-export new-sha new-dir))

    (let [old-results (run-all-tests-in old-dir)
          new-results (run-all-tests-in new-dir)]
      (outer-join (->> old-results
                       (map (fn [res]
                              {:name (:name res)
                               :old (dissoc res :name)}))
                       (into #{}))
                  (->> new-results
                       (map (fn [res]
                              {:name (:name res)
                               :new (dissoc res :name)}))
                       (into #{}))
                  [:name]))))

(defn emit-junit-xml [combined-test-results]
  (xml/indent-str
   (xml/sexp-as-element
    [:testsuite
     (for [{:keys [name new errors]} combined-test-results]
       (let [[test-ns test-name] (s/split name #"\/")]
         [:testcase {:name test-name
                     :classname test-ns
                     :time (:mean new)}
          (for [error errors]
            [:error error])]))])))

(defn perf-cli-command [& args]
  (let [[cmd & args] args]
    (case cmd
      "list" (pr (list-tests))
      "run" (pr (run-test (first args)))
      "regress" (println (->> (regress (first args) (second args))
                              (map check-combined-test-result)
                              emit-junit-xml))
      (println-err "bad command"))))

(defn -main [& args]
  (apply perf-cli-command args)
  (shutdown-agents))
