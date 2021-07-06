(ns puppetlabs.puppetdb.integration.time-shift-export
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.time-shift-export :refer [time-shift-export-wrapper]]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :as utils])
  (:import (java.io BufferedReader FileReader)))

(defn execute-cmd [& args]
  (apply shell/sh args))

(defn file-content
  [file-path]
  (with-open [reader (BufferedReader. (FileReader. file-path))]
    (json/parse-stream reader true)))

(def input-archive "input-archive.tgz")
(def report-file "input-archive/reports/report.json")
(def output-archive "output-archive.tgz")
(def fact "input-archive/facts/facts.json")
(def catalog "input-archive/catalogs/catalog.json")
(def metadata "input-archive/export-metadata.json")
(def input-archive-path "dev-resources/time-shift-export")

(defn set-up
  [f]
  (execute-cmd "tar" "-czvf" input-archive (str "--directory=" input-archive-path) catalog fact report-file metadata)
  (f))

(defn clean-up
  [f]
  (f)
  (shell/sh "rm" "-rf"
            "input-archive.tgz"
            "output-archive.tgz"
            "input-archive"
            "2-1621030860000_report_8_host-0.json"
            "1-1621030800000_facts_5_host-8.json"
            "0-1621030740000_catalog_9_host-8.json"))

(use-fixtures :each set-up clean-up)

(defn test-timestamps
  [report-file fact catalog]
      (testing "Shifted report timestamps"
        (is (= "2021-05-14T22:21:00.000Z" (:producer_timestamp (file-content report-file))))
        (is (= "2021-05-14T22:21:00.000Z" (:start_time (file-content report-file))))
        (is (= "2021-05-14T22:19:00.000Z" (:end_time (file-content report-file))))
        (is (= "2021-05-14T22:20:01.000Z" (get-in (file-content report-file) [:logs 0 :time])))
        (is (= "2021-05-14T22:19:00.000Z" (get-in (file-content report-file) [:logs 1 :time])))
        (is (= "2021-05-14T22:19:00.000Z" (get-in (file-content report-file) [:resources 0 :timestamp])))
        (is (= "2021-05-14T22:22:00.000Z" (get-in (file-content report-file) [:resources 0 :events 0 :timestamp]))))

      (testing "Shifted facts timestamp"
        (is (= "2021-05-14T22:20:00.000Z" (:producer_timestamp (file-content fact)))))

      (testing "Shifted catalog timestamp"
        (is (= "2021-05-14T22:19:00.000Z" (:producer_timestamp (file-content catalog)))))
  )

(defn time-shift
  [& args]
  (with-redefs [utils/try-process-cli (fn [body] (body))]
    (time-shift-export-wrapper args)))

(deftest ^:integration export-time-shift
  (testing "when the time shift date is provided"
    (time-shift "-i" input-archive "-o" output-archive "-t" "2021-05-14T22:22:00.000Z")

    (execute-cmd "tar" "-xvzf" output-archive)

    (test-timestamps report-file fact catalog)

    (testing "Export metadata is unchanged"
      (is (= (file-content (str input-archive-path "/" metadata)) (file-content metadata))))))

(deftest ^:integration export-time-shift-format-stockpile
  (testing "when the time shift date and format stockpile is provided"
    (let [renamed_report "2-1621030860000_report_8_host-0.json"
          renamed_fact "1-1621030800000_facts_5_host-8.json"
          renamed_catalog "0-1621030740000_catalog_9_host-8.json"]

      (time-shift "-i" input-archive "-o" output-archive "-t" "2021-05-14T22:22:00.000Z" "-f" "stockpile")

      (execute-cmd "tar" "-xvzf" output-archive)

      (test-timestamps renamed_report renamed_fact renamed_catalog)

      (testing "Export metadata is not archived"
        (is (= false (.exists (io/as-file metadata))))))))
