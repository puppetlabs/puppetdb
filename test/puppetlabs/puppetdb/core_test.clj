(ns puppetlabs.puppetdb.core-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.cli.util :as cliu]
   [puppetlabs.puppetdb.core :as core]
   [puppetlabs.trapperkeeper.logging :refer [root-logger]]
   [puppetlabs.trapperkeeper.testutils.logging
    :refer [with-log-level with-logged-event-maps]]))

(deftest handling-of-invalid-jdk-versions
  (let [logs? #{"services" "upgrade"}]
    (letfn [(check [subcommand version]
              (let [err (java.io.StringWriter.)
                    [result err log]
                    (binding [*err* err]
                      (with-log-level (root-logger) :error 
                        (with-logged-event-maps log
                          ;; Relies on clj's left-to-right arg eval order
                          [(with-redefs [cliu/java-version (constantly version)]
                             (core/run-subcommand subcommand []))
                           (str err) @log])))]
                (is (= cliu/err-exit-status result))
                ;; Right now the output may also include clojure warning lines
                (is (re-find #"(?s)(?:^|\n)error: PuppetDB doesn't support.*" err))
                (if-not (logs? subcommand)
                  (is (= [] log))
                  (do
                    (is (= 1 (count log)))
                    (let [[{:keys [logger level message]}] log]
                      (is (= "puppetlabs.puppetdb.cli.tk-util" logger))
                      (is (= :error level))
                      (is (re-find #"PuppetDB doesn't support.*" message)))))))]
      (doseq [cmd ["help" "version" "benchmark" "services" "upgrade"]]
        (check cmd "1.5.0")))))
