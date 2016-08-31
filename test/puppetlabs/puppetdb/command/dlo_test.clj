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
   [puppetlabs.puppetdb.queue :refer [cmdref->entry cons-attempt store-command]]
   [puppetlabs.puppetdb.testutils :refer [ordered-matches?]]
   [puppetlabs.puppetdb.testutils.nio :refer [call-with-temp-dir-path]]
   [puppetlabs.stockpile.queue :as stock]))

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
           cmd-bytes (-> cmd json/generate-string (.getBytes "UTF-8"))
           cmd-size (count cmd-bytes)
           cmdref (store-command q "replace catalog" 9 (:certname cmd)
                                 (java.io.ByteArrayInputStream. cmd-bytes))
           content (slurp (stock/stream q (cmdref->entry cmdref)))
           cval (fn [name]
                  (counters/value (counter reg (cons "puppetlabs.puppetdb.dlo"
                                                     name))))]
       (is (zero? (cval ["global" "messages"])))
       (is (zero? (cval ["global" "filesize"])))
       (is (zero? (cval ["replace catalog" "messages"])))
       (is (zero? (cval ["replace catalog" "filesize"])))
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
         (is (= content (-> (:command discards) .toFile io/reader slurp))))
       (is (= 1 (cval ["global" "messages"])))
       (is (= cmd-size (cval ["global" "filesize"])))
       (is (= 1 (cval ["replace catalog" "messages"])))
       (is (= cmd-size (cval ["replace catalog" "filesize"])))))))

(deftest discard-stream
  (call-with-temp-dir-path
   (get-path "target")
   "pdb-test-"
   (fn [tmpdir]
     (let [reg (:registry (new-metrics "puppetlabs.puppetdb.dlo" :jmx? false))
           dlo (dlo/initialize (.resolve tmpdir "dlo") reg)
           cmd-bytes (.getBytes "what a mess" "UTF-8")
           cmd-size (count cmd-bytes)
           cval (fn [name]
                  (counters/value (counter reg (cons "puppetlabs.puppetdb.dlo"
                                                     name))))]
       (is (zero? (cval ["global" "messages"])))
       (is (zero? (cval ["global" "filesize"])))
       (is (zero? (cval ["unknown" "messages"])))
       (is (zero? (cval ["unknown" "filesize"])))
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
         (is (= "what a mess"
                (-> (:command discards) .toFile io/reader slurp))))
       (is (= 1 (cval ["global" "messages"])))
       (is (= cmd-size (cval ["global" "filesize"])))
       (is (= 1 (cval ["unknown" "messages"])))
       (is (= cmd-size (cval ["unknown" "filesize"])))))))
