(ns puppetlabs.puppetdb.mq-listener-test
  (:import [java.util.concurrent TimeUnit])
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging :refer [*logger-factory*]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.mq-listener :refer :all]
            [puppetlabs.trapperkeeper.testutils.logging :refer [atom-logger
                                                                with-logging-to-atom
                                                                with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.test-protocols :as test-protos]))

(def max-log-check-attempts 1000)
(def log-sleep-duration-in-ms 10)

(defn await-log-entry
  "This function looks for a log entry in `log-atom`. If one is not
  found it sleeps for `log-sleep-duration-in-ms` and repeats the
  check. It does this `max-log-check-attempts` times and will
  eventually throw an exception if it's not found"
  [log-atom]
  (loop [times 0]
    (when-not (seq @log-atom)
      (if (> times max-log-check-attempts)
        (-> (* log-sleep-duration-in-ms max-log-check-attempts)
            (format "Log entry not found after %s ms")
            RuntimeException.
            throw)
        (do
          (Thread/sleep log-sleep-duration-in-ms)
          (recur (inc times)))))))

(def critical-error-pred (comp #{:fatal :error} :level))

(deftest threadpool-logging
  (testing "successful message"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable critical-error-pred
        (with-logging-to-atom "puppetlabs.puppetdb.mq-listener" log-output
          (let [{:keys [threadpool semaphore] :as threadpool-ctx} (create-command-handler-threadpool 1)
                handler-fn (tu/mock-fn)
                identity-handler (command-submission-handler threadpool-ctx handler-fn)]
            (try

              (is (= 1 (.availablePermits semaphore)))
              (is (not (test-protos/called? handler-fn)))

              (identity-handler "this arg does nothing")

              (is (.tryAcquire semaphore 1 TimeUnit/SECONDS)
                  "Failed to aquire token from the semaphore")
              (is (= [] @log-output))
              (is (test-protos/called? handler-fn))

              (finally
                (.shutdownNow threadpool))))))))

  (testing "failure of thread"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable (every-pred critical-error-pred
                                                      (comp (complement #(.startsWith % "Broken"))
                                                            :message))
        (with-logging-to-atom "puppetlabs.puppetdb.mq-listener" log-output
          (let [{:keys [threadpool semaphore] :as threadpool-ctx} (create-command-handler-threadpool 1)
                always-error-handler (command-submission-handler threadpool-ctx
                                                                 (fn [_] (throw (RuntimeException. "Broken!"))))]
            (try
              (is (= 1 (.availablePermits semaphore)))
              (always-error-handler "this arg is ignored")

              ;; Releasing the semaphore happens right before the
              ;; message is logged with the uncaughtExceptionHandler
              (is (.tryAcquire semaphore 1 TimeUnit/SECONDS)
                  "Failed to aquire token from the semaphore")

              (await-log-entry log-output)

              (is (= 1 (count @log-output)))

              (let [log-event (first @log-output)]
                (is (= "ERROR"
                       (-> log-event
                           .getLevel
                           str)))

                (is (= "cmd-proc-thread-1" (.getThreadName log-event))))

              (finally
                (.shutdownNow threadpool))))))))

  (defn not-submitted? [message]
    (str/includes? message "not submitted"))

  (testing "threadpool shutdown"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable (every-pred critical-error-pred
                                                      (comp (complement not-submitted?) :message))
       (with-logging-to-atom "puppetlabs.puppetdb.mq-listener" log-output
         (let [{:keys [threadpool semaphore] :as threadpool-ctx} (create-command-handler-threadpool 1)
               handler-fn (tu/mock-fn)
               never-executed-handler (command-submission-handler threadpool-ctx handler-fn)]
           (try
             (is (= 1 (.availablePermits semaphore)))

             (.shutdownNow threadpool)
             (is (.awaitTermination threadpool 1 TimeUnit/SECONDS)
                 "threadpool not shutdown")

             (never-executed-handler "this arg is ignored")

             (is (.tryAcquire semaphore 1 TimeUnit/SECONDS)
                 "Failed to aquire token from the semaphore")
             (is (= 1 (count @log-output)))

             (let [log-event (first @log-output)]
               (is (= "ERROR"
                      (-> log-event
                          .getLevel
                          str)))
               (is (str/includes? (.getMessage log-event) "not submitted")))

             (is (not (test-protos/called? handler-fn)))

             (finally
               (.shutdownNow threadpool)))))))))
