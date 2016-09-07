(ns puppetlabs.puppetdb.command.dlo-test
  (:require
   [clj-time.coerce :as coerce-time]
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
   [puppetlabs.puppetdb.queue :refer [cmdref->entry cons-attempt store-command]]
   [puppetlabs.puppetdb.testutils :refer [ordered-matches?]]
   [puppetlabs.puppetdb.testutils.nio :refer [call-with-temp-dir-path]]
   [puppetlabs.stockpile.queue :as stock])
  (import
   [java.nio.file Files]
   [java.util Arrays]))

(defn reg-counter-val [registry suffix]
  (let [mname (str "puppetlabs.puppetdb.dlo." suffix)]
    (when-let [ctr (get (.getCounters registry) mname)]
      (counters/value ctr))))

(defn met-counter-val [metrics & names]
  (when-let [m (get-in metrics names)]
    (counters/value m)))

(defn entry->bytes [q entry]
  (let [result (java.io.ByteArrayOutputStream.)
        buf (byte-array (* 4 1024))]
    (with-open [stream (stock/stream q entry)]
      (loop [n (.read stream buf)]
        (if (= -1 n)
          (.toByteArray result)
          (do
            (.write result buf 0 n)
            (recur (.read stream buf))))))))

(defn store-catalog [q dlo]
  (let [cmd (get-in wire-catalogs [9 :basic])
        cmd-bytes (-> cmd json/generate-string (.getBytes "UTF-8"))]
    (store-command q "replace catalog" 9 (:certname cmd)
                   (java.io.ByteArrayInputStream. cmd-bytes))))

(defn err-attempt-line? [n s]
  (-> (str "Attempt " n " @ \\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\dZ")
      re-pattern
      (re-matches s)))

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
           content (entry->bytes q (cmdref->entry cmdref))
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
         (is (Arrays/equals content
                            (Files/readAllBytes (:command discards)))))

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
           cmd-bytes (.getBytes "what a mess" "UTF-8")
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
         (is (Arrays/equals cmd-bytes
                            (Files/readAllBytes (:command discards)))))

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
