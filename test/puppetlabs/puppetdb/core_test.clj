(ns puppetlabs.puppetdb.core-test
  (:require [puppetlabs.puppetdb.core :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :as pllog]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.utils-test :refer [jdk-1-6-version
                                                    jdk-1-7-version
                                                    unsupported-regex]]))

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

(defn valid-unsupported-pred [regex]
  (fn [[category level _ msg :as foo]]
    (and (= "puppetlabs.puppetdb.utils"
            category)
         (= :error
            level)
         (re-find regex msg))))

(deftest jdk-fail-message
  (testing "No unsupported message when using 1.7"
    (let [success? (atom false)
          fail? (atom false)
          jdk-version  jdk-1-7-version]
      (with-redefs [kitchensink/java-version jdk-version]
        (pllog/with-log-output log
          (is (nil?
               (re-find unsupported-regex
                        (tu/with-err-str
                          (with-out-str
                            (run-command #(reset! success? true)
                                         #(reset! fail? true)
                                         ["version"]))))))
          (is (true? @success?))
          (is (false? @fail?))
          (is (not-any? (valid-unsupported-pred unsupported-regex) @log))))))

  (testing "fail message appears in log and stdout when using JDK 1.6"
    (let [exec-path (atom [])
          jdk-version  jdk-1-6-version]
      (with-redefs [kitchensink/java-version jdk-version]
        (pllog/with-log-output log
          (is (re-find unsupported-regex
                       (tu/with-err-str
                         (with-out-str
                           (run-command #(swap! exec-path conj :success)
                                        #(swap! exec-path conj :fail)
                                        ["version"])))))
          ;;The code should call the fail-fn first, then the
          ;;success-fn
          (is (= [:fail :success] @exec-path))
          (is (some (valid-unsupported-pred unsupported-regex) @log)))))))
