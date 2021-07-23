(ns puppetlabs.puppetdb.cli.pdb-dataset-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [puppetlabs.puppetdb.cli.pdb-dataset :refer [validate-cli!]]
    [puppetlabs.puppetdb.utils :as utils :refer [with-captured-throw]]
    [clojure.string :as str]
    [puppetlabs.kitchensink.core :as kitchensink])
  (:import
    [clojure.lang ExceptionInfo]))

(defn dataset
  [& args]
  (with-redefs [utils/try-process-cli (fn [body] (body))]
    (validate-cli! args)))

(deftest returned-error
  (testing "when required input parameter is missing"
    (let [response (with-captured-throw (dataset))]
      (is (= ExceptionInfo (class response)))
      (when (= ExceptionInfo (class response))
        (is (= ::kitchensink/cli-error (:kind (ex-data response))))
        (is (str/includes? (:msg (ex-data response))
                           "Missing required argument '--dumpfile'!")))))

  (testing "when provided data is invalid"
    (let [response (with-captured-throw (dataset "-t" "invalid-date" "-d" "dumpfile"))]
      (is (= ExceptionInfo (class response)))
      (when (= ExceptionInfo (class response))
        (is (= ::kitchensink/cli-error (:kind (ex-data response))))
        (is (str/includes? (:msg (ex-data response))
                           "Error: time shift date must be in UTC format!"))))))

(deftest correct-usage
  (testing "when the time shift date is provided"
    (let [response (dataset "-t" "2021-04-14T18:56" "-d" "dumpfile")]
      (is (= {:dumpfile     "dumpfile"
              :timeshift-to #inst "2021-04-14T18:56:00.000000000-00:00"} response)))))
