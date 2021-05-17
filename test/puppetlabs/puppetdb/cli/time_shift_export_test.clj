(ns puppetlabs.puppetdb.cli.time-shift-export-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [puppetlabs.puppetdb.cli.time-shift-export :refer [time-shift-export-wrapper, file-exists?]]
    [puppetlabs.puppetdb.utils :as utils :refer [with-captured-throw]]
    [clojure.string :as str]
    [puppetlabs.kitchensink.core :as kitchensink])
  (:import
    [clojure.lang ExceptionInfo]))

(defn time-shift
  [& args]
  (with-redefs [utils/try-process-cli (fn [body] (body))]
    (time-shift-export-wrapper args)))

(deftest returned-error
  (testing "when required input parameter is missing"
    (let [response (with-captured-throw (time-shift))]
      (is (= ExceptionInfo (class response)))
      (when (= ExceptionInfo (class response))
        (is (= ::kitchensink/cli-error (:kind (ex-data response))))
        (is (str/includes? (:msg (ex-data response))
                           "Missing required argument '--input'!")))))

  (testing "when required input parameter is invalid"
    (with-redefs [file-exists? (fn [_] false)]
      (let [response (with-captured-throw (time-shift "-i" "/path/archive"))]
        (is (= ExceptionInfo (class response)))
        (when (= ExceptionInfo (class response))
          (is (= ::kitchensink/cli-error (:kind (ex-data response))))
          (is (str/includes? (:msg (ex-data response))
                             "Error: input archive path: /path/archive, must be valid!"))))))

  (testing "when provided data is invalid"
    (with-redefs [file-exists? (fn [_] true)]
      (let [response (with-captured-throw (time-shift "-i" "/path/archive" "-t" "invalid-date"))]
        (is (= ExceptionInfo (class response)))
        (when (= ExceptionInfo (class response))
          (is (= ::kitchensink/cli-error (:kind (ex-data response))))
          (is (str/includes? (:msg (ex-data response))
                             "Error: time shift date: invalid-date, must be in UTC format!")))))))
