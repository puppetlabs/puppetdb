(ns puppetlabs.puppetdb.threadpool-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.threadpool
             :refer [dochan gated-threadpool shutdown]]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.utils :refer [await-ref-state
                                               noisy-future]]
            [puppetlabs.trapperkeeper.testutils.logging
             :refer [with-logging-to-atom with-log-suppressed-unless-notable]]
            [puppetlabs.puppetdb.test-protocols :as test-protos]
            [clojure.string :as str]
            [puppetlabs.puppetdb.testutils.log :as tlog])
  (:import
   (clojure.lang ExceptionInfo)
   (java.util.concurrent TimeUnit)))

(defn wrap-out-chan [out-chan f]
  (fn [cmd]
    (async/>!! out-chan (f cmd))))

(deftest exec-from-channel-test

  (testing "basic exec lifecycle"
    (let [counter (atom 0)
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          worker-fn (wrap-out-chan
                     out-chan
                     (fn [{:keys [id]}]
                       (swap! counter inc)
                       [:done id]))]

      (with-open [gtp (gated-threadpool 1 "test-pool-%d" 1000)]
        (let [blocked? (atom false)
              fut (future
                    (dochan gtp worker-fn in-chan blocked?))]
          (async/>!! in-chan {:id :a})
          (is (= [:done :a] (async/<!! out-chan)))
          (is (= 1 @counter))
          (async/close! in-chan)
          (is (not= ::timed-out (deref fut tu/default-timeout-ms ::timed-out)))
          (is (true? (future-done? fut)))))))

  (testing "message in-flight shutdown"
    (let [counter (atom 0)
          stop-here (promise)
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          worker-fn (wrap-out-chan
                     out-chan
                     (fn [some-command]
                       @stop-here
                       (swap! counter inc)
                       [:done (:foo some-command)]))]

      (with-open [gtp (gated-threadpool 1 "test-pool-%d" 1000)]
        (let [blocked? (atom false)
              fut (future
                    (dochan gtp worker-fn in-chan blocked?))]

          (async/>!! in-chan {:foo 1})
          (async/close! in-chan)

          (is (= 0 @counter))

          (deliver stop-here true)

          (is (= [:done 1] (async/<!! out-chan)))

          (is (not= ::timed-out (deref fut tu/default-timeout-ms ::timed-out)))
          (is (true? (future-done? fut)))))))

  (testing "blocking put on gated threadpool"
    (let [seen (atom [])
          in-flight-promises {:a (promise)
                              :b (promise)
                              :c (promise)}

          stop-here {:a (promise)
                     :b (promise)
                     :c (promise)}

          in-chan (async/chan 10)
          out-chan (async/chan 10)
          worker-fn (wrap-out-chan
                     out-chan
                     (fn [{:keys [id]}]
                       (swap! seen conj id)
                       (deliver (get in-flight-promises id) true)
                       @(get stop-here id)
                       [:done id]))]

      (with-open [gtp (gated-threadpool 2 "test-pool-%d" 1000)]
        (let [blocked? (atom false)
              fut (future
                    (dochan gtp worker-fn in-chan blocked?))]

          (async/>!! in-chan {:id :a})
          (is (= true (deref (:a in-flight-promises) tu/default-timeout-ms ::not-found)))

          (async/>!! in-chan {:id :b})
          (is (= true (deref (:b in-flight-promises) tu/default-timeout-ms ::not-found)))

          (async/>!! in-chan {:id :c})
          (async/close! in-chan)

          (is (= ::not-found (deref (:c in-flight-promises) 100 ::not-found)))
          (is (= [:a :b] @seen))

          (deliver (get stop-here :a) true)

          (is (= [:done :a] (async/<!! out-chan)))
          (is (= true (deref (:c in-flight-promises) tu/default-timeout-ms ::not-found)))
          (is (= [:a :b :c] @seen))

          (deliver (get stop-here :b) true)
          (is (= [:done :b] (async/<!! out-chan)))

          (deliver (get stop-here :c) true)
          (is (= [:done :c] (async/<!! out-chan)))

          (is (not= ::timed-out (deref fut tu/default-timeout-ms ::timed-out)))
          (is (true? (future-done? fut))))))))


(deftest dochan-blocking
  (testing "blocking and unblocking"
    (let [counter (atom 0)
          in-chan (async/chan 10)
          out-chan (async/chan 10)
          worker-fn (wrap-out-chan
                      out-chan
                      (fn [{:keys [id]}]
                        (swap! counter inc)
                        [:done id]))
          blocked? (atom true)]

      (with-open [gtp (gated-threadpool 1 "test-pool-%d" 1000)]
        (let [fut (noisy-future
                    (dochan gtp worker-fn in-chan blocked?)
                    ::finished)
              channel (noisy-future (async/<!! out-chan))]

          (async/>!! in-chan {:id :a})

          (testing "blocking"
            (is (= "Did not wake up!" (deref channel 500 "Did not wake up!")))
            (is (= 0 @counter)))

          (testing "unblocking"
            (reset! blocked? false)
            (locking blocked? (.notifyAll blocked?))
            (is (= [:done :a] (deref channel)))
            (is (= 1 @counter)))

          (async/close! in-chan)
          (is (= ::finished (deref fut tu/default-timeout-ms ::timed-out)))
          (is (future-done? fut)))))))


(def max-log-msg-wait-ms (* 10 1000))

(defn not-submitted? [message]
  (str/includes? message "not submitted"))

(deftest threadpool-logging
  (testing "successful message"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable tlog/critical-errors
        (with-logging-to-atom "puppetlabs.puppetdb.threadpool" log-output
          (let [{:keys [threadpool semaphore] :as threadpool-ctx} (gated-threadpool 1 "testpool-%d" 5000)
                handler-fn (tu/mock-fn)]
            (try

              (is (= 1 (.availablePermits semaphore)))
              (is (not (test-protos/called? handler-fn)))

              (.execute threadpool-ctx handler-fn)

              (is (.tryAcquire semaphore 1 TimeUnit/SECONDS)
                  "Failed to aquire token from the semaphore")
              (is (= [] @log-output))
              (is (test-protos/called? handler-fn))

              (finally
                (.shutdownNow threadpool))))))))

  (testing "failure of thread"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable (every-pred tlog/critical-errors
                                                      (complement (tlog/starting-with "Broken")))
        (with-logging-to-atom "puppetlabs.puppetdb.threadpool" log-output
          (let [{:keys [threadpool semaphore] :as threadpool-ctx} (gated-threadpool 1 "testpool-%d" 5000)]
            (try
              (is (= 1 (.availablePermits semaphore)))
              (.execute threadpool-ctx
                        (fn [] (throw (RuntimeException. "Broken!"))))

              ;; Releasing the semaphore happens right before the
              ;; message is logged with the uncaughtExceptionHandler
              (is (.tryAcquire semaphore 1 TimeUnit/SECONDS)
                  "Failed to aquire token from the semaphore")

              (when-not (is (not (= ::timeout
                                    (await-ref-state log-output #(= (count %) 1)
                                                     max-log-msg-wait-ms ::timeout))))
                (println "Timed out waiting for log output"))

              (when-not (is (= 1 (count @log-output)))
                (binding [*out* *err*]
                  (println "Unexpected log output:")
                  (println @log-output)))

              (let [log-event (first @log-output)]

                (is (= "ERROR" (-> log-event .getLevel str)))

                (when-not (is (re-find #"error from thread testpool-1"
                                       (.getMessage log-event)))
                  (binding [*out* *err*]
                    (println "Unexpected log event" log-event))))

              (finally
                (.shutdownNow threadpool))))))))

  (testing "threadpool shutdown"
    (let [log-output (atom [])]
      (with-log-suppressed-unless-notable (every-pred tlog/critical-errors
                                                      (comp (complement not-submitted?) :message))
       (with-logging-to-atom "puppetlabs.puppetdb.threadpool" log-output
         (let [{:keys [threadpool semaphore] :as threadpool-ctx} (gated-threadpool 1 "testpool-%d" 5000)
               handler-fn (tu/mock-fn)
               in-chan (async/chan 10)]

           (try

             (is (= 1 (.availablePermits semaphore)))

             ;; Acquire a permit, so that shutdown can't grab the permit
             (is (.tryAcquire semaphore 1 TimeUnit/SECONDS))

             (shutdown threadpool-ctx)
             (is (.awaitTermination threadpool 1 TimeUnit/SECONDS)
                 "threadpool not shutdown")

             ;; Although we are shutting the threadpool down, it will
             ;; wait for in flight work to finish, it's possible that
             ;; we could release the permit and attempt to put new
             ;; work on the threadpool.
             (is (nil? (.release semaphore)))
             (async/>!! in-chan "message")

             ;; Shutting down the threadpool when the channel is not
             ;; empty will result in an exception, below ensures that
             ;; happens
             (let [blocked? (atom false)
                   ex (try
                        (dochan threadpool-ctx handler-fn in-chan blocked?)
                        (catch Throwable ex ex))]
               (is (= ExceptionInfo (class ex)))
               (when (= ExceptionInfo (class ex))
                 (let [data (ex-data ex)]
                   (is (= :puppetlabs.puppetdb.threadpool/rejected (:kind data)))
                   (is (= "message" (:message data)))
                   (is (str/starts-with? (ex-message ex)
                                         "Threadpool shutting down")))))
             (finally
               (.shutdownNow threadpool)))))))))
