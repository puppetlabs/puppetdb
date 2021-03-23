(ns puppetlabs.puppetdb.command.dlo-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [metrics.counters :as counters :refer [counter]]
   [puppetlabs.kitchensink.core :refer [timestamp]]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.command.dlo :as dlo]
   [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
   [puppetlabs.puppetdb.metrics.core :refer [new-metrics]]
   [puppetlabs.puppetdb.nio :refer [get-path]]
   [puppetlabs.puppetdb.queue :refer [cmdref->entry cons-attempt store-command create-command-req]]
   [puppetlabs.puppetdb.testutils :refer [ordered-matches?]]
   [puppetlabs.puppetdb.testutils.nio :refer [call-with-temp-dir-path]]
   [puppetlabs.puppetdb.testutils.queue :refer [catalog->command-req]]
   [puppetlabs.puppetdb.time :as coerce-time]
   [puppetlabs.stockpile.queue :as stock])
  (:import
   [java.nio.file Files]))

(defn reg-counter-val [registry suffix]
  (let [mname (str "puppetlabs.puppetdb.dlo." suffix)]
    (when-let [ctr (get (.getCounters registry) mname)]
      (counters/value ctr))))

(defn met-counter-val [metrics & names]
  (when-let [m (get-in metrics names)]
    (counters/value m)))

(defn entry->str [q entry]
  (with-open [stream (stock/stream q entry)]
    (slurp stream)))

(defn store-catalog [q dlo]
  (->> (get-in wire-catalogs [9 :basic])
       (catalog->command-req 9)
       (store-command q)))

(defn err-attempt-line? [n s]
  (-> (str "Attempt " n " @ \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\dZ")
      re-pattern
      (re-matches s)))

(deftest parse-cmd-filename-behavior
  (let [r0 (-> 0 coerce-time/from-long timestamp)
        r10 (-> 10 coerce-time/from-long timestamp)]

    (are [cmd-info metadata-str] (= cmd-info (#'dlo/parse-cmd-filename metadata-str))

      {:received r0 :version 0 :command "replace catalog" :certname "foo" :producer-ts nil :compression ""}
      "0-0_catalog_0_foo.json"

      {:received r0 :version 0 :command "replace catalog" :certname "foo.json" :producer-ts nil :compression ""}
      "0-0_catalog_0_foo.json.json"

      {:received r10 :version 10 :command "replace catalog" :certname "foo" :producer-ts nil :compression ""}
      "10-10_catalog_10_foo.json"

      {:received r10 :version 42 :command "replace catalog" :certname "foo" :producer-ts nil :compression ""}
      "10-10_catalog_42_foo.json"

      {:received r10 :version 10 :command "unknown" :certname "foo" :producer-ts nil :compression ""}
      "10-10_unknown_10_foo.json")

    (is (not (#'dlo/parse-cmd-filename "0-0_foo_0_foo.json")))))

(deftest discard-cmdref
  (call-with-temp-dir-path
   (get-path "target")
   "pdb-test-"
   (fn [tmpdir]
     (let [q (stock/create (.resolve tmpdir "q"))
           reg (:registry (new-metrics "puppetlabs.puppetdb.dlo" :jmx? false))
           dlo (dlo/initialize (.resolve tmpdir "dlo") reg)
           cmd (get-in wire-catalogs [9 :basic])
           cmdref (store-catalog q dlo)
           content (entry->str q (cmdref->entry cmdref))
           cmd-size (count content)
           regval (partial reg-counter-val reg)
           metval (fn [& names] (apply met-counter-val @(:metrics dlo) names))]

       (is (zero? (regval "global.messages")))
       (is (zero? (metval "global" :messages)))
       (is (zero? (regval "global.filesize")))
       (is (zero? (metval "global" :filesize)))

       (is (not (regval "replace catalog.messages")))
       (is (not (metval "replace catalog" :messages)))
       (is (not (regval "replace catalog.filesize")))
       (is (not (metval "replace catalog" :filesize)))

       (is (not (regval "unknown.messages")))
       (is (not (metval "unknown" :messages)))
       (is (not (regval "unknown.filesize")))
       (is (not (metval "unknown" :filesize)))

       (let [cmdref (-> cmdref
                        (cons-attempt (Exception. "thud-1"))
                        (cons-attempt (Exception. "thud-2"))
                        (cons-attempt (Exception. "thud-3")))
             discards (dlo/discard-cmdref cmdref q dlo)]
         (is (ordered-matches?
              [#(err-attempt-line? 3 %)
               #(= "java.lang.Exception: thud-3" %)
               #(err-attempt-line? 2 %)
               #(= "java.lang.Exception: thud-2" %)
               #(err-attempt-line? 1 %)
               #(= "java.lang.Exception: thud-1" %)]
              (-> (:info discards) .toFile io/reader line-seq)))
         (is (= content (slurp (.toFile (:command discards))))))

       (is (= 1 (regval "global.messages")))
       (is (= 1 (metval "global" :messages)))
       (is (= cmd-size (regval "global.filesize")))
       (is (= cmd-size (metval "global" :filesize)))

       (is (= 1 (regval "replace catalog.messages")))
       (is (= 1 (metval "replace catalog" :messages)))
       (is (= cmd-size (regval "replace catalog.filesize")))
       (is (= cmd-size (metval "replace catalog" :filesize)))

       (is (not (regval "unknown.messages")))
       (is (not (metval "unknown" :messages)))
       (is (not (regval "unknown.filesize")))
       (is (not (metval "unknown" :filesize)))))))

(deftest discard-stream
  (call-with-temp-dir-path
   (get-path "target")
   "pdb-test-"
   (fn [tmpdir]
     (let [reg (:registry (new-metrics "puppetlabs.puppetdb.dlo" :jmx? false))
           dlo (dlo/initialize (.resolve tmpdir "dlo") reg)
           cmd-str "what a mess"
           cmd-bytes (.getBytes cmd-str "UTF-8")
           cmd-size (count cmd-bytes)
           regval (partial reg-counter-val reg)
           metval (fn [& names] (apply met-counter-val @(:metrics dlo) names))]

       (is (zero? (regval "global.messages")))
       (is (zero? (metval "global" :messages)))
       (is (zero? (regval "global.filesize")))
       (is (zero? (metval "global" :filesize)))

       (is (not (regval "replace catalog.messages")))
       (is (not (metval "replace catalog" :messages)))
       (is (not (regval "replace catalog.filesize")))
       (is (not (metval "replace catalog" :filesize)))

       (is (not (regval "unknown.messages")))
       (is (not (metval "unknown" :messages)))
       (is (not (regval "unknown.filesize")))
       (is (not (metval "unknown" :filesize)))

       (let [attempts [{:time (timestamp) :exception (Exception. "thud-3")}
                       {:time (timestamp) :exception (Exception. "thud-2")}
                       {:time (timestamp) :exception (Exception. "thud-1")}]
             discards (dlo/discard-bytes cmd-bytes 1 (timestamp) attempts dlo)]
         (is (ordered-matches?
              [#(err-attempt-line? 3 %)
               #(= "java.lang.Exception: thud-3" %)
               #(err-attempt-line? 2 %)
               #(= "java.lang.Exception: thud-2" %)
               #(err-attempt-line? 1 %)
               #(= "java.lang.Exception: thud-1" %)]
              (-> (:info discards) .toFile io/reader line-seq)))
         (is (= cmd-str (slurp (.toFile (:command discards))))))

       (is (= 1 (regval "global.messages")))
       (is (= 1 (metval "global" :messages)))
       (is (= cmd-size (regval "global.filesize")))
       (is (= cmd-size (metval "global" :filesize)))

       (is (not (regval "replace catalog.messages")))
       (is (not (metval "replace catalog" :messages)))
       (is (not (regval "replace catalog.filesize")))
       (is (not (metval "replace catalog" :filesize)))

       (is (= 1 (regval "unknown.messages")))
       (is (= 1 (metval "unknown" :messages)))
       (is (= cmd-size (regval "unknown.filesize")))
       (is (= cmd-size (metval "unknown" :filesize)))))))

(deftest initialize-with-existing-messages
  (call-with-temp-dir-path
   (get-path "target")
   "pdb-test-"
   (fn [tmpdir]
     (let [dlo-path (.resolve tmpdir "dlo")
           cat-size (atom nil)
           unk-bytes (.getBytes "what a mess" "UTF-8")
           unk-size (count unk-bytes)]
       ;; Discard a couple of things
       (let [reg (:registry (new-metrics "puppetlabs.puppetdb.dlo" :jmx? false))
             dlo (dlo/initialize dlo-path reg)
             q (stock/create (.resolve tmpdir "q"))]
         (let [cmdref (store-catalog q dlo)]
           (reset! cat-size (->> cmdref cmdref->entry (entry->str q) count))
           (dlo/discard-cmdref cmdref q dlo))
         (dlo/discard-bytes unk-bytes
                            1 (timestamp)
                            [{:time (timestamp) :exception (Exception. "thud-3")}
                             {:time (timestamp) :exception (Exception. "thud-2")}
                             {:time (timestamp) :exception (Exception. "thud-1")}]
                            dlo))
       ;; See if initialize finds them
       (let [reg (:registry (new-metrics "puppetlabs.puppetdb.dlo" :jmx? false))
             dlo (dlo/initialize dlo-path reg)
             cat-size @cat-size
             glob-size (+ cat-size unk-size)
             regval (partial reg-counter-val reg)
             metval (fn [& names] (apply met-counter-val @(:metrics dlo) names))]

         (is (= 2 (regval "global.messages")))
         (is (= 2 (metval "global" :messages)))
         (is (= glob-size (regval "global.filesize")))
         (is (= glob-size (metval "global" :filesize)))

         (is (= 1 (regval "replace catalog.messages")))
         (is (= 1 (metval "replace catalog" :messages)))
         (is (= cat-size (regval "replace catalog.filesize")))
         (is (= cat-size (metval "replace catalog" :filesize)))

         (is (= 1 (regval "unknown.messages")))
         (is (= 1 (metval "unknown" :messages)))
         (is (= unk-size (regval "unknown.filesize")))
         (is (= unk-size (metval "unknown" :filesize))))))))
