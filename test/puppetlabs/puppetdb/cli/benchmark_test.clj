(ns puppetlabs.puppetdb.cli.benchmark-test
  (:require [clojure.data]
            [clojure.pprint]
            [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [me.raynes.fs :as fs]
            [murphy :refer [with-final]]
            [puppetlabs.puppetdb.cli.benchmark :as benchmark
             :refer [no-open-options populate-hosts]]
            [puppetlabs.puppetdb.cli.generate :refer [build-parameters weigh]]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.lint :refer [ignore-value]]
            [puppetlabs.puppetdb.nio :refer [copts copt-replace get-path]]
            [puppetlabs.trapperkeeper.config :as config]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.cli :refer [get-nodes example-catalog
                                                       example-report example-facts
                                                       example-certname]]
            [puppetlabs.puppetdb.testutils.nio :refer [create-temp-dir]]
            [puppetlabs.puppetdb.time :as time]
            [puppetlabs.puppetdb.utils :as utils :refer [with-captured-throw]]
            [puppetlabs.kitchensink.core :as ks]
            [taoensso.nippy :as nippy])
  (:import
   [clojure.lang ExceptionInfo]
   [java.nio.file Files]))

(defn mock-submit-record-fn [submitted-records entity]
  (fn [base-url _certname version payload-string ssl-opts]
    (swap! submitted-records conj
           {:entity entity
            :base-url base-url
            :version version
            :payload-string payload-string
            :payload (keywordize-keys payload-string)
            :ssl-opts ssl-opts})))

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
                  utils/try-process-cli (fn ([f] (f)) ([f _] (f)))
                  benchmark/benchmark-shutdown-timeout tu/default-timeout-ms
                  ;; disable catalog/reports submission delay to avoid slowing down tests
                  benchmark/random-cmd-delay (constantly 0)]
      (f submitted-records (benchmark/send-commands-wrapper cli-args)))))

(deftest progressing-timestamp-nummsgs
  (doseq [end-in [0 -3 3 14]]
    (let [now (time/now)
          end-in-days (time/days end-in)
          get-timestamp (benchmark/progressing-timestamp 1 (* 14 48) 30 end-in-days)
          initial-timestamp (get-timestamp)]
      (is (time/before? (-> end-in-days
                            time/from-now
                            (time/minus (time/days 14))) initial-timestamp))
      (is (time/after? (-> now
                           (time/plus (time/days end-in))
                           (time/minus (time/days 14))
                           (time/plus (time/minutes 31))) initial-timestamp))
      ;; start at 2 because of first and last invocations
      (doseq [_ (range 2 (* 14 48))]
        (get-timestamp))
      (let [final-timestamp (get-timestamp)
            before-time (time/plus now end-in-days)
            after-time (time/plus (time/now) end-in-days)]
        (is (or (time/equal? before-time final-timestamp)
                (time/before? before-time final-timestamp)))
        (is (or (time/equal? after-time final-timestamp)
                (time/after? after-time final-timestamp)))))))

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
                         "Error: must specify --nummsgs, --runinterval, or --querier.")))))

(deftest nummsgs-or-runinterval-is-required
  (let [x (with-captured-throw (benchmark-nummsgs {}
                                                  "--config" "anything.ini"
                                                  "--numhosts" "42"))]

    (is (= ExceptionInfo (class x)))
    (when (= ExceptionInfo (class x))
      (is (= ::ks/cli-error (:kind (ex-data x))))
      (is (str/includes? (:msg (ex-data x))
                         "Error: must specify --nummsgs, --runinterval, or --querier.")))))

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

(deftest toggle-catalog-edges
  (testing "default: no catalog edges included"
    (let [submitted (benchmark-nummsgs {}
                                       "--config" "anything.ini"
                                       "--numhosts" "2"
                                       "--nummsgs" "3")
          catalogs (filter #(= :catalog (:entity %))
                           submitted)]

      (is (every? (fn [c] (zero? (count (get-in c [:payload :edges]))))
                  catalogs))))

  (testing "catalog edges included when requested"
    (let [submitted (benchmark-nummsgs {}
                                       "--config" "anything.ini"
                                       "--numhosts" "2"
                                       "--nummsgs" "3"
                                       "--include-catalog-edges")
          catalogs (filter #(= :catalog (:entity %))
                           submitted)]

      (is (every? (fn [c] (pos? (count (get-in c [:payload :edges]))))
                  catalogs)))))

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
                                     "--rand-catalogs" "100:1"
                                     "--rand-facts" "100:100")
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

(deftest benchmark-runs-at-correct-rate
  (call-with-benchmark-status
   {}
   ["--config" "anything.ini"
    "--numhosts" "600"
    "--runinterval" "1"]
   ;; This should generate 30 messages per second (10 hosts per second, 3
   ;; messages per host).
   (fn [submitted {:keys [stop]}]
     (let [start (System/currentTimeMillis)
           enough-records (* 3 30) ; 3 seconds
           finished (promise)
           watch-key (Object.)
           watcher (fn [_k _ref _old new]
                     (when (>= (count new) enough-records)
                       (deliver finished true)))]
       (add-watch submitted watch-key watcher)
       (when-not (>= (count @submitted) enough-records) ; avoid add-watch race
         (deref finished tu/default-timeout-ms nil))
       ;; Allow a ~33% margin of error to account for jitter in the simulation
       ;; timer.
       (let [elapsed (/ (- (System/currentTimeMillis) start) 1000.0)]
         (is (<= 2 elapsed 4)))
       (stop)))))

(deftest rand-catalog-mutation-test
  (let [catalog {"certname"           "host-1"
                 "catalog_uuid"       "512d24ae-8999-4f12-bda0-1e5d57c0b5cc"
                 "producer"           "puppet-primary-1"
                 "hash"               "a923c88272cbf195c4c1f3138090a1c6a64712a3"
                 "transaction_uuid"   "65998cdc-a66f-40a9-b4fd-2b4c51384764"
                 "producer_timestamp" "2023-07-20T17:00:11.947Z"
                 "environment"        "production"
                 "code_id"            "100891c7504e899c86bad9ce60c27b29ae5c21ec"
                 "version"            "1690231634"
                 "resources"          [{"type"  "Class"
                                        "title" "class-aclass-0"
                                        "file"  "thing"
                                        "line"  123
                                        "tags"  ["one" "two"]
                                        "parameters" {"abcdef" "one"
                                                      "ghiklm" "two"}}
                                       {"type"  "Atype"
                                        "title" "resource-atypetitle-0"
                                        "file"  "otherthing"
                                        "line"  456
                                        "tags"  ["three" "four"]
                                        "parameters" {"123456" "one"
                                                      "foo_bar" "two"}}
                                       {"type"  "Btype"
                                        "title" "resource-btypetitle-1"
                                        "file"  "otherthing2"
                                        "line"  789
                                        "tags"  ["five" "six"]
                                        "parameters" {"baz_bif" "abcd"
                                                      "abc123" "fgh"}}
                                       {"type"  "Ctype"
                                        "title" "resource-ctypetitle-2"
                                        "file"  "otherthing3"
                                        "line"  9102
                                        "tags"  ["three" "seven"]
                                        "parameters" {"aaabbb" "val3"
                                                      "another" "val4"}}]
                 "edges"              [{"source" {"type"  "Class"
                                                  "title" "class-aclass-0"}
                                        "target" {"type"  "Atype"
                                                  "title" "resource-atypetitle-0"}
                                        "relationship" "contains"}
                                       {"source" {"type"  "Class"
                                                  "title" "class-aclass-0"}
                                        "target" {"type"  "Btype"
                                                  "title" "resource-btypetitle-1"}
                                        "relationship" "contains"}
                                       {"source" {"type"  "Class"
                                                  "title" "class-aclass-0"}
                                        "target" {"type"  "Ctype"
                                                  "title" "resource-ctypetitle-2"}
                                        "relationship" "contains"}]
                 "job_id"             nil}
        mutated (benchmark/rand-catalog-mutation catalog 1 true)
        checked-keys (clojure.walk/walk (fn [[k _]] [k (string? k)]) identity mutated)
        symbol-keys (filter (fn [[_ is-string]] (not is-string)) checked-keys)]
    (is (empty? symbol-keys) "Mutating a catalog unexpectedly produced these keys as symbols instead of strings.")
    (is (seq? (mutated "resources")) "transformed resources back into a sequence")
    (is (<= 3 (count (mutated "resources")) 5) "removed, added or mutated a resource")
    (is (= (- (count (mutated "resources")) 1) (count (mutated "edges")))
        "modified edges in sync with resources")
    (testing "long runs"
      (testing "without edges"
        (let [catalog (assoc catalog "edges" [])
              mutations (doall
                          (repeatedly 10
                                    #(benchmark/rand-catalog-mutation catalog 1000 false)))
              rcounts (map #(count (% "resources")) mutations)
              weights (map #(weigh %) mutations)
              avg #(quot (reduce + %) (count %))]
          ;;(println {:cat-weight (weigh catalog)})
          ;;(println {:rcounts rcounts})
          ;;(println {:weights weights})
          (is (<= 5 (avg rcounts) 40))
          (is (<= 1000 (avg weights) 8000))
          (let [orig-change-resources benchmark/change-resources
                res (atom {:add 0
                           :del 0
                           :mod 0})]
            (with-redefs [benchmark/change-resources
                          (fn [op resource]
                            (swap! res update op inc)
                            (orig-change-resources op resource))]
              (let [long-mutation (benchmark/rand-catalog-mutation catalog 100000 false)
                    resources (long-mutation "resources")
                    ;; add an arbitrary "buffer" to account for the fact that deletes
                    ;; when we already have just 1 resource can become modifies
                    max-expected-resources (+ 500
                                              (max 0 (- (:add @res) (:del @res))))]
                ;;(println {:lm-rcount (count resources) :lm-weight (weigh long-mutation)})
                (is (<= 1 (count resources) max-expected-resources))
                (is (<= 200 (weigh long-mutation) (* 150 max-expected-resources))))))))
      (testing "with edges"
        (let [orig-change-resources benchmark/change-resources
              res (atom {:add 0
                         :del 0
                         :mod 0})]
          (with-redefs [benchmark/change-resources
                        (fn [op resource]
                          (swap! res update op inc)
                          (orig-change-resources op resource))]
            (let [mutated (benchmark/rand-catalog-mutation catalog 10000 true)
                  rcount (count (mutated "resources"))
                  ecount (count (mutated "edges"))
                  extra-resources (max 0 (- (:add @res) (:del @res)))]
              ;;(clojure.pprint/pprint mutated)
              ;;(println {:edges-rcount rcount :edges-ecount ecount})
              (is (<= 1 rcount (+ 200 extra-resources)))
              (is (= (- rcount 1) ecount)))))))))

(deftest resource-has-blob?-test
  (let [resource {"type"  "Class"
                  "title" "aclass"
                  "file"  "thing"
                  "line"  123
                  "tags"  ["one" "two"]
                  "parameters" {"a" "one"
                                "b" "two"}}
        resource-with-blob (update resource "parameters" #(assoc % "content_blob_foo" "BLOB"))
        resource-with-empty (assoc resource "parameters" {})]
    (is (not (benchmark/resource-has-blob? resource)))
    (is (benchmark/resource-has-blob? resource-with-blob))
    (is (not (benchmark/resource-has-blob? resource-with-empty)))))

(deftest modify-title-test
  (let [middle "abcdefghijklmn"
        title (str "resource-" middle "-12")
        new-title (benchmark/modify-title title "test")
        [match g1] (re-matches #"test-(\w+)-\d+" new-title)]
    (is (not (nil? match)))
    (is (not= middle g1))
    (is (= (count title) (count new-title)))))

(deftest clone-resource-test
  (let [r1 {"type"  "Class"
            "title" "aclass"
            "file"  "thing"
            "line"  123
            "tags"  ["one" "two"]
            "parameters" {"abcdef" "one"
                          "ghiklm" "two"
                          "nlopqr" "three"}}
        cloned (benchmark/clone-resource r1)]
    (is (= "Class" (cloned "type")) "same type")
    (is (str/starts-with? (cloned "title") "clone-") "marked title")
    (is (seq (cloned "parameters")) "some parameters created")
    (is (empty? (remove string? (keys cloned))) "all string keys")))

(deftest del-resource-test
  (let [k1 {"type" "Class" "title" "aclass"}
        r1 {"type"  "Class"
            "title" "aclass"
            "file"  "thing"
            "line"  123
            "tags"  ["one" "two"]
            "parameters" {"a" "one"
                          "b" "two"}}
        k2 {"type" "Class" "title" "bclass"}
        r2 {"type"  "Class"
            "title" "bclass"
            "file"  "thing2"
            "line"  1234
            "tags"  ["one" "two"]
            "parameters" {"c" "one"
                          "d" "two"}}
        work-cat {:resource-hash {k1 r1 k2 r2}
                  :include-edges false
                  :edges []}]
    (testing "not including edges"
      (is (= (assoc work-cat :resource-hash {k2 r2})
             (benchmark/del-resource work-cat k1)) "removes resource")
      (is (= (assoc work-cat :resource-hash {k1 r1})
             (benchmark/del-resource work-cat k2)) "removes resource")
      (is (= work-cat (benchmark/del-resource work-cat {"type" "none" "title" "not"}))
          "does nothing if key not present"))
    (testing "including edges"
      (let [edges [{"source" {"type"  "Class"
                              "title" "aclass"}
                    "target" {"type"  "Class"
                              "title" "bclass"}
                    "relationship" "contains"}]
            work-cat (assoc work-cat
                            :include-edges true
                            :edges edges)]
        (is (= work-cat (benchmark/del-resource work-cat k1))
            "does nothing because no cloned resource to delete")
        (testing "and add-resource has added cloned resources at some point"
          (let [k3 {"type" "Class" "title" "clone-bclass"}
                r3 {"type"  "Class"
                    "title" "clone-bclass"
                    "file"  "thing3"
                    "line"  456
                    "tags"  ["one" "two"]
                    "parameters" {"e" "three"
                                  "f" "four"}}
                e3 {"source" {"type"  "Class"
                               "title" "aclass"}
                     "target" {"type"  "Class"
                               "title" "clone-bclass"}
                     "relationship" "contains"}
                work-cat-with-clone (assoc work-cat
                                     :resource-hash {k1 r1 k2 r2 k3 r3}
                                     :edges (conj edges e3))]
          (is (= work-cat (benchmark/del-resource work-cat-with-clone k1))
              "deletes k3 and edge instead")))))))

(deftest add-resource-test
  (let [k1 {"type" "Class" "title" "aclass"}
        r1 {"type"  "Class"
            "title" "aclass"
            "file"  "thing"
            "line"  123
            "tags"  ["one" "two"]
            "parameters" {"abcdef" "one"
                          "ghiklm" "two"}}
        k2 {"type" "Class" "title" "bclass"}
        r2 {"type"  "Class"
            "title" "bclass"
            "file"  "thing2"
            "line"  1234
            "tags"  ["one" "two"]
            "parameters" {"abcdef" "one"
                          "ghiklm" "two"}}
        work-cat {:resource-hash {k1 r1 k2 r2}
                  :include-edges false
                  :edges []
                  :original-keys [k1 k2]}]
    (testing "not including edges"
      (let [added-cat (benchmark/add-resource work-cat r1)]
        (is (= 3 (count (:resource-hash added-cat))) "adds a resource")
        (is (empty? (:edges added-cat)) "does not add an edge")))
    (testing "with edges"
      (let [edges [{"source" {"type"  "Class"
                              "title" "aclass"}
                    "target" {"type"  "Class"
                              "title" "bclass"}
                    "relationship" "contains"}]
            work-cat (assoc work-cat
                            :include-edges true
                            :edges edges)
            added-cat (benchmark/add-resource work-cat r1)]
        (is (= 3 (count (:resource-hash added-cat))) "adds a resource")
        (is (= 2 (count (:edges added-cat))) "adds an edge")
        (testing "forms edges with original-keys"
          (let [itered-cat (nth (iterate
                                  #(benchmark/add-resource % (rand-nth [r1 r2]))
                                  added-cat)
                                97)]
            (is (= 100 (count (:resource-hash itered-cat))) "adds 98 resources")
            (is (= 99 (count (:edges itered-cat))) "adds 98 edges")
            (is (= 99 (count (filter (fn [{:strs [source]}]
                                        (.contains [k1 k2] source))
                                      (:edges itered-cat))))
                "all edges formed with non-cloned sources")))))))

(deftest mod-resource-test
  (let [k1 {"type" "Class" "title" "aclass"}
        r1 {"type"  "Class"
            "title" "aclass"
            "file"  "thing"
            "line"  123
            "tags"  ["one" "two"]
            "parameters" {"a" "one"
                          "b" "two"}}
        k2 {"type" "Class" "title" "bclass"}
        r2 {"type"  "Class"
            "title" "bclass"
            "file"  "thing2"
            "line"  1234
            "tags"  ["one" "two"]
            "parameters" {"c" "one"
                          "d" "two"}}
        work-cat {:resource-hash {k1 r1 k2 r2}
                  :include-edges false
                  :edges []}]
    (testing "parameter change"
      (let [modded-cat (benchmark/mod-resource work-cat k1)]
        (is (= 2 (count (:resource-hash modded-cat))) "same count")
        (is (not= (r1 "parameters")
                  (get-in modded-cat [:resource-hash k1 "parameters"]))
            "parameters have changed")))))

(deftest touch-parameters-test
  (let [psmall {"a" "b"}
        pbig (build-parameters 2000)
        pbig-count (count pbig)
        pbig-weight (weigh pbig)

        ps-mutated (benchmark/touch-parameters psmall)
        pb-mutated (benchmark/touch-parameters pbig)]
    (is (= {} (benchmark/touch-parameters {})) "Handles the empty case.")
    (is (= 1 (count ps-mutated)))
    (is (= 2 (weigh ps-mutated)))
    (is (not= psmall ps-mutated))
    (is (= pbig-count (count pb-mutated)))
    (is (= pbig-weight (weigh pb-mutated)))
    (is (not= pbig pb-mutated))))

(deftest rebuild-parameters-test
  (let [psmall {"a" "b"}
        pbig (build-parameters 2000)
        pbig-count (count pbig)
        pbig-weight (weigh pbig)

        ps-rebuilt (benchmark/rebuild-parameters psmall)
        pb-rebuilt (benchmark/rebuild-parameters pbig)]
    (is (= {} (benchmark/rebuild-parameters {})) "Handles the empty case.")
    (is (= 1 (count ps-rebuilt)))
    (is (= 6 (weigh ps-rebuilt)))
    (is (= pbig-count (count pb-rebuilt)))
    (is (= pbig-weight (weigh pb-rebuilt)))
    (is (not= pbig pb-rebuilt))
    (let [pb-keys (set (keys pbig))
          pb-vals (set (vals pbig))
          rebuilt-keys (set (keys pb-rebuilt))
          rebuilt-vals (set (vals pb-rebuilt))]
      (is (empty? (clojure.set/intersection pb-keys rebuilt-keys)))
      (let [val-intersect (clojure.set/intersection pb-vals rebuilt-vals)]
        (is (or (empty? val-intersect)
                (= #{""} val-intersect)))))))

(deftest change-resources-test
  (testing "does not delete last resource"
    (let [k1 {"type" "Class" "title" "aclass"}
          r1 {"type"  "Class"
              "title" "aclass"
              "file"  "thing"
              "line"  123
              "tags"  ["one" "two"]
              "parameters" {"a" "one"
                            "b" "two"}}
          work-cat {:resource-hash {k1 r1}
                    :include-edges false
                    :edges []}
          mutated (benchmark/change-resources :del work-cat)
          rmod (first (vals (:resource-hash mutated)))]
      (is (not (nil? rmod)) "did not delete last resource")
      (is (not (= (r1 "parameters") (rmod "parameters"))) "modified instead"))))

(deftest populate-hosts-behavior
  (testing "without command data"
    (with-final [storage (create-temp-dir "pdb-bench-") :always fs/delete-dir]
      (let [host-maps (populate-hosts 3 0 "pdb.test" false nil nil nil storage)]
        (is (= [{:host "host-0" :catalog nil :factset nil :report nil}
                {:host "host-1" :catalog nil :factset nil :report nil}
                {:host "host-2" :catalog nil :factset nil :report nil}]
               host-maps)))))

  (let [catalogs [{"certname" "foo" "edges" [{"a" "b"}] "resources" [{"tags" ["a"]}]}
                  {"certname" "bar" "edges" [{"a" "b"}] "resources" [{"tags" ["a"]}]}
                  {"certname" "baz" "edges" [{"a" "b"}] "resources" [{"tags" ["a"]}]}]
        reports [{"certname" "foo" "status" "unchanged"}
                 {"certname" "bar" "status" "unchanged"}
                 {"certname" "baz" "status" "unchanged"}]
        facts [{"certname" "foo" "values" {}}
               {"certname" "bar" "values" {}}
               {"certname" "baz" "values" {}}]]

    (testing "from scratch"
      (with-final [storage (create-temp-dir "pdb-bench-") :always fs/delete-dir]
        (is (= [{:host "host-0"
                 :catalog {"certname" "host-0"
                           "edges" []
                           "resources" [{"tags" ["a" "pdb.test"]}]}
                 :factset {"certname" "host-0" "values" {}}
                 :report {"certname" "host-0" "status" "unchanged"}}
                {:host "host-1"
                 :catalog {"certname" "host-1"
                           "edges" []
                           "resources" [{"tags" ["a" "pdb.test"]}]}
                 :factset {"certname" "host-1" "values" {}}
                 :report {"certname" "host-1" "status" "unchanged"}}
                {:host "host-2"
                 :catalog {"certname" "host-2"
                           "edges" []
                           "resources" [{"tags" ["a" "pdb.test"]}]}
                 :factset {"certname" "host-2" "values" {}}
                 :report {"certname" "host-2" "status" "unchanged"}}]
               (populate-hosts 3 0 "pdb.test" false catalogs reports facts storage)))))

    (testing "loads existing data"
      (with-final [storage (create-temp-dir "pdb-bench-") :always fs/delete-dir]
        (let [hosts (populate-hosts 3 0 "pdb.test" false catalogs reports facts storage)]
          (doseq [info hosts
                  :let [path (#'benchmark/host->host-path (:host info) storage)]]
            (Files/write path ^"[B" (nippy/freeze info) no-open-options))
          (is (= hosts
                 (->> (populate-hosts 3 0 "pdb.test" false catalogs reports facts storage)
                      (sort-by :host)))))))

    (testing "respects current data selection"
      ;; populate-hosts only handles augmentation, pruning is handled by simulators
      (with-final [storage (create-temp-dir "pdb-bench-") :always fs/delete-dir]
        (let [hosts (populate-hosts 3 0 "pdb.test" false catalogs reports nil storage)
              with-facts (populate-hosts 3 0 "pdb.test" false catalogs reports facts storage)]
          (doseq [info hosts
                  :let [path (#'benchmark/host->host-path (:host info) storage)]]
            (Files/write path ^"[B" (nippy/freeze info) no-open-options))
          (is (= with-facts
                 (->> (populate-hosts 3 0 "pdb.test" false catalogs reports facts storage)
                      (sort-by :host)))))))))

(deftest test-create-storage-dir
  (testing "tmp-dir creation"
    (let [path (benchmark/create-storage-dir nil)]
      (tu/delete-on-exit (.toFile path))
      (is (re-matches #"^/tmp/pdb-bench-.*" (str path)))
      (is (fs/exists? path))))
  (testing "given dir that exists"
    (let [path (benchmark/create-storage-dir (str (tu/temp-dir)))]
      (tu/delete-on-exit (.toFile path))
      (is (re-matches #"^/tmp/tu-tmpdir.*" (str path)))))
  (testing "given dir to create"
    (let [dirname (format "/tmp/pdb-bench-tu-%s" (System/currentTimeMillis))]
      (is (not (fs/exists? dirname)))
      (let [path (benchmark/create-storage-dir dirname)]
        (tu/delete-on-exit (.toFile path))
        (is (= dirname (str path)))
        (is (fs/exists? path))))))

(defn recover-preserved-host-maps
  "Given a Path to a directory, returns an array of all thawed host-* host maps."
  [storage-dir]
  (->> (fs/glob (.resolve storage-dir "host-*"))
       (map #(nippy/thaw (Files/readAllBytes (.toPath %))))))

(defn get-preserved-host-map
  "Given a Path to a directory and a host file name, return the thawed host map."
  [storage-dir host]
  (->> (#'benchmark/host->host-path host storage-dir)
    Files/readAllBytes
    nippy/thaw))

(deftest host-map-preservation
  (let [tempdir-path (.toPath (tu/temp-dir))
        simulation-path (.resolve tempdir-path "sim-dir")
        ;; Store some host-maps
        submitted (benchmark-nummsgs {}
                                     "--config" "anything.ini"
                                     "--numhosts" "10"
                                     "--nummsgs" "1"
                                     "--simulation-dir" (str simulation-path))
        preserved-host-maps (recover-preserved-host-maps simulation-path)]
    (is (= 30 (count submitted)))
    (is (= 10 (count preserved-host-maps)))
    (testing "re-running benchmark uses preserved state"
      (let [restarted-submissions (benchmark-nummsgs
                                    {}
                                    "--config" "anything.ini"
                                    "--numhosts" "20"
                                    "--nummsgs" "1"
                                    "--simulation-dir" (str simulation-path))
            submitted-host-maps (->> restarted-submissions
                                  (reduce (fn [m i]
                                            (let [entity (:entity i)
                                                  payload (:payload i)
                                                  host (:certname payload)]
                                              (assoc-in m [host entity]
                                                        (clojure.walk/stringify-keys
                                                          payload))))
                                         {})
                                  (map (fn [[host host-map]]
                                         (assoc host-map :host host))))
            ;; benchmark/update-report-resources without jitter
            nullify-report-timestamps-fn (fn [report]
                                           (let [update-timestamps-fn
                                                  (fn [resources-or-events]
                                                    (map #(assoc % "timestamp" nil)
                                                         resources-or-events))]
                                             (->> (get report "resources")
                                                  update-timestamps-fn
                                                  (map #(update % "events" update-timestamps-fn)))))
            ;; Clear out all of the timestamps they don't thaw with nippy 3.1.1, because
            ;; it doesn't have org.time.joda.DateTime in its allowlists. And even if it did
            ;; we change all of these values before submitting, so they would be different
            ;; regardless
            clean-hostmaps-fn (fn [host-maps]
                                (map (fn [m]
                                       (assoc
                                         m
                                         :catalog
                                          (-> (:catalog m)
                                              (assoc "producer_timestamp" nil)
                                              (assoc "transaction_uuid" nil)
                                              (assoc "producer" nil))
                                         :factset
                                          (-> (:factset m)
                                              (assoc "producer_timestamp" nil)
                                              (assoc "producer" nil))
                                         :report
                                          (-> (:report m)
                                              (update "resources" nullify-report-timestamps-fn)
                                              (assoc "configuration_version" nil)
                                              (assoc "transaction_uuid" nil)
                                              (assoc "start_time" nil)
                                              (assoc "end_time" nil)
                                              (assoc "producer_timestamp" nil)
                                              (assoc "producer" nil))))
                                     host-maps))
            submitted-cleaned (clean-hostmaps-fn submitted-host-maps)
            preserved-cleaned (clean-hostmaps-fn preserved-host-maps)]
        (doseq [n (range 0 10)]
          (let [preserved (nth preserved-cleaned n)
                submitted (first (filter #(= (:host %) (:host preserved))
                                         submitted-cleaned))
                [only-in-preserved only-in-submitted _]
                  (clojure.data/diff preserved submitted)
                equal (= preserved submitted)]
            ;; output can be huge, so try to narrow it down some
            (is equal)
            (when-not equal
              (clojure.pprint/pprint
                {:failed {:host (:host preserved)
                          :diff {:only-in-preserved only-in-preserved
                                 :only-in-submitted only-in-submitted}}}))))))
    (testing "re-running benchmark restricted does not lose preserved state"
      (let [;; just submit catalogs
            restarted-submissions (benchmark-nummsgs
                                    {}
                                    "--config" "anything.ini"
                                    "--catalogs" (str "resources/" (:catalogs benchmark/default-data-paths))
                                    "--numhosts" "1"
                                    "--nummsgs" "1"
                                    "--simulation-dir" (str simulation-path))
            preserved-host-0 (get-preserved-host-map simulation-path "host-0")]
        (is (= 1 (count restarted-submissions)))
        (is (= :catalog (:entity (first restarted-submissions))))
        (is (not (nil? (:factset preserved-host-0))))
        (is (not (nil? (:report preserved-host-0))))
        (is (not (nil? (:catalog preserved-host-0))))))
    (testing "re-running benchmark with additional data adds it to preserved state"
      (let [;; just submit catalogs
            _ (benchmark-nummsgs
                                    {}
                                    "--config" "anything.ini"
                                    "--catalogs" (str "resources/" (:catalogs benchmark/default-data-paths))
                                    "--numhosts" "1"
                                    "--offset" "100"
                                    "--nummsgs" "1"
                                    "--simulation-dir" (str simulation-path))
            ;; then submit catalogs and facts
            _ (benchmark-nummsgs
                                    {}
                                    "--config" "anything.ini"
                                    "--catalogs" (str "resources/" (:catalogs benchmark/default-data-paths))
                                    "--facts" (str "resources/" (:facts benchmark/default-data-paths))
                                    "--numhosts" "1"
                                    "--offset" "100"
                                    "--nummsgs" "1"
                                    "--simulation-dir" (str simulation-path))
            preserved-host-100 (get-preserved-host-map simulation-path "host-100")]
        (is (not (nil? (:factset preserved-host-100))))
        (is (not (nil? (:catalog preserved-host-100))))
        (is (nil? (:report preserved-host-100)))))))

(deftest rand-catalogs-and-facts-arg-handling
  (testing "cli parsing"
    (let [base-args ["--config" "config.ini"
                     "--numhosts" "10"
                     "--nummsgs" "20"]
          parse-rand-args (fn [& rand-args]
                            (#'benchmark/validate-cli!
                              (reduce conj base-args rand-args)))]
      (with-redefs [config/load-config (fn [_] {})]
        (testing "of --rand-catalogs"
          (is (= [[1.0 1]] (:rand-catalogs (parse-rand-args "--rand-catalogs=100:1"))))
          (is (= [[0.01 200]] (:rand-catalogs (parse-rand-args "--rand-catalogs=1:200"))))
          (is (= [[0.153 300]] (:rand-catalogs (parse-rand-args "--rand-catalogs=15.3:300"))))
          (is (= [[0.2 500]
                  [1.0 2]] (:rand-catalogs (parse-rand-args
                                                 "--rand-catalogs=20:500"
                                                 "--rand-catalogs=80:2"))))
          (is (= [[0.001 1000]
                  [0.151 100]
                  [0.207 23]] (:rand-catalogs (parse-rand-args
                                                 "--rand-catalogs=.1:1000"
                                                 "--rand-catalogs=15:100"
                                                 "--rand-catalogs=5.6:23")))))
        (testing "of --rand-facts"
          (is (= [[1.0 0.01]] (:rand-facts (parse-rand-args "--rand-facts=100:1"))))
          (is (= [[0.01 1.0]] (:rand-facts (parse-rand-args "--rand-facts=1:100"))))
          (is (= [[0.2 0.5]
                  [1.0 0.75]] (:rand-facts (parse-rand-args
                                                 "--rand-facts=20:50"
                                                 "--rand-facts=80:75"))))))))
  (testing "selection"
    (is (= 0 (#'benchmark/pick-random-count-or-change []))
        "returns 0 if nothing to choose")
    (is (= 0 (#'benchmark/pick-random-count-or-change [[0 1]]))
        "returns 0 if no chance")
    (is (= 42 (#'benchmark/pick-random-count-or-change [[1.0 42]]))
        "returns 42 for 100% chance")))
