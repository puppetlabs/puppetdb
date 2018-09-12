(ns puppetlabs.puppetdb.core-test
  (:require [puppetlabs.puppetdb.core :as core :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :as tu]))

(defn ignore-exception [f]
  (try
    (f)
    (catch Exception _
      ;;do nothing
      )))

(defn invoke-and-throw [ex-msg]
  (let [called? (atom false)]
    [called?
     (fn []
       (reset! called? true)
       (throw (ex-info ex-msg {})))]))

(deftest usage-message
  (let [success? (atom false)
        [fail? fail-fn] (invoke-and-throw "fail-fn called")]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"fail-fn called"
                          (with-out-str
                            (run-command #(reset! success? true) fail-fn ["something-random"]))))
    (is (false? @success?))
    (is (true? @fail?))
    (is (re-find #"For help on a given subcommand.*"
                 (with-out-str
                   (binding [*err* *out*]
                     (ignore-exception
                      (fn []
                        (run-command (constantly nil) fail-fn ["something-random"])))))))))

(deftest successful-command-invocation
  (let [success? (atom false)
        fail? (atom false)]
    (with-out-str
      (run-command #(reset! success? true)
                   #(reset! fail? true)
                   ["version"]))
    (is (true? @success?))
    (is (false? @fail?))))

(deftest handling-of-jdk-versions
  (letfn [(check [version invalid?]
            (let [err (java.io.StringWriter.)
                  success? (atom false)
                  fail? (atom false)
                  [success? fail? err log]
                  (binding [*err* err]
                    (pllog/with-log-output log
                      (with-redefs [core/java-version (constantly version)]
                        (run-command #(reset! success? true)
                                     #(reset! fail? true)
                                     ["version"]))
                      [@success? @fail? (str err) @log]))]
              (if-not invalid?
                (do
                  (is success?)
                  (is (not fail?))
                  (is (= "" err))
                  (is (= [] log)))
                (do
                  (is (not success?))
                  (is fail?)
                  (is (re-matches #"(?s)error: PuppetDB doesn't support.*" err))
                  (is (= 1 (count log)))
                  (let [[[category level _ msg]] log]
                    (is (= "puppetlabs.puppetdb.utils" category))
                    (is (= :error level))
                    (is (re-matches #"PuppetDB doesn't support.*" msg)))))))]
    (check "1.5.0" true)
    (check "1.8.0" false)
    (check "1.10.0" false)
    (check "huh?" false)))
