(ns puppetlabs.puppetdb.command.dlo-test
  (:require
   [clojure.test :refer :all]
   [metrics.counters :as counters :refer [counter]]
   [puppetlabs.kitchensink.core :refer [timestamp]]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.command.dlo :as dlo]
   [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
   [puppetlabs.puppetdb.metrics.core :refer [new-metrics]]
   [puppetlabs.puppetdb.mq-listener :refer [annotate-with-attempt]]
   [puppetlabs.puppetdb.nio :refer [get-path]]
   [puppetlabs.puppetdb.queue :refer [store-command]]
   [puppetlabs.puppetdb.testutils.nio :refer [call-with-temp-dir-path]]
   [stockpile :as stock]))

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
           cval (fn [name]
                  (counters/value (counter reg (cons "puppetlabs.puppetdb.dlo"
                                                     name))))]
       (is (zero? (cval ["global" "messages"])))
       (is (zero? (cval ["global" "filesize"])))
       (is (zero? (cval ["replace catalog" "messages"])))
       (is (zero? (cval ["replace catalog" "filesize"])))
       (dlo/discard-cmdref (-> cmdref
                               (annotate-with-attempt (Exception. "thud-1"))
                               (annotate-with-attempt (Exception. "thud-2")))
                           (Exception. "thud-3")
                           q dlo)
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
       (dlo/discard-bytes cmd-bytes 1 (timestamp) [(Exception. "thud-1")] dlo)
       (is (= 1 (cval ["global" "messages"])))
       (is (= cmd-size (cval ["global" "filesize"])))
       (is (= 1 (cval ["unknown" "messages"])))
       (is (= cmd-size (cval ["unknown" "filesize"])))))))
