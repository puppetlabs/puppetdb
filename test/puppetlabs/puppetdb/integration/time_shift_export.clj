(ns puppetlabs.puppetdb.integration.time-shift-export
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.time-shift-export :refer [time-shift-export-wrapper]]
            [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.utils :as utils]))

(defn clean-up
  [f]
  (f)
  (shell/sh "rm" "-rf" "input-archive.tgz" "output-archive.tgz" "reports" "facts" "catalogs" "export-metadata.json"))

(use-fixtures :once clean-up)

(defn execute-cmd [& args]
  (let [{:keys [err exit] :as info} (apply shell/sh args)]
    (when-not (zero? exit)
      (throw (Exception.
              (str (first (split-with string? args))
                   (pr-str (select-keys info [:exit :err]))))))))

(defn time-shift
  [& args]
  (with-redefs [utils/try-process-cli (fn [body] (body))]
    (time-shift-export-wrapper args)))

(defn file-content
  [file-path]
  (with-open [reader (java.io.BufferedReader. (java.io.FileReader. file-path))]
    (json/parse-stream reader true)))

(deftest ^:integration export-time-shift
  (testing "when the time shift date is provided"
    (let [input-archive "input-archive.tgz"
          output-archive "output-archive.tgz"
          report "reports/report.json"
          fact "facts/facts.json"
          catalog "catalogs/catalog.json"
          metadata "export-metadata.json"
          input-archive-path "dev-resources/time-shift-export/input-archive"]

      (execute-cmd "tar" "-czvf" input-archive (str "--directory=" input-archive-path) catalog fact report metadata)

      (time-shift "-i" input-archive "-o" output-archive "-t" "2021-05-14T22:22:00.000Z")

      (execute-cmd "tar" "-xvzf" output-archive)

      (testing "Shifted report timestamps"
        (is (= "2021-05-14T22:21:00.000Z" (:producer_timestamp (file-content report))))
        (is (= "2021-05-14T22:21:00.000Z" (:start_time (file-content report))))
        (is (= "2021-05-14T22:19:00.000Z" (:end_time (file-content report))))
        (is (= "2021-05-14T22:20:01.000Z" (get-in (file-content report) [:logs 0 :time])))
        (is (= "2021-05-14T22:19:00.000Z" (get-in (file-content report) [:logs 1 :time])))
        (is (= "2021-05-14T22:19:00.000Z" (get-in (file-content report) [:resources 0 :timestamp])))
        (is (= "2021-05-14T22:22:00.000Z" (get-in (file-content report) [:resources 0 :events 0 :timestamp]))))

      (testing "Shifted facts timestamp"
        (is (= "2021-05-14T22:20:00.000Z" (:producer_timestamp (file-content fact)))))

      (testing "Shifted catalog timestamp"
        (is (= "2021-05-14T22:19:00.000Z" (:producer_timestamp (file-content catalog)))))

      (testing "Export metadata is unchanged"
        (is (= (file-content (str input-archive-path "/" metadata)) (file-content metadata)))))))
