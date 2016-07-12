(ns puppetlabs.puppetdb.threadpool
  (:import [javax.jms ExceptionListener JMSException MessageListener Session
            ConnectionFactory Connection Queue Message]
           [java.util.concurrent Semaphore ThreadPoolExecutor TimeUnit SynchronousQueue
            RejectedExecutionException ExecutorService]
           [org.apache.commons.lang3.concurrent BasicThreadFactory BasicThreadFactory$Builder])
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]))

(def logging-exception-handler
  "Exception handler that ensures any uncaught exception that occurs
  on this thread is logged"
  (reify Thread$UncaughtExceptionHandler
    (uncaughtException [_ thread throwable]
      (log/error throwable
                 (i18n/trs "Error processing command")))))

(defn thread-factory
  "Creates a command thread factory, wrapping the
  `pool-thread-factory`. Using this thread factory ensures that we
  have distinguishable names for threads in this pool and that all
  uncaught exceptions are logged as errors. Without this uncaught
  exceptions get the default which is standard error"
  [threadpool-name-pattern pool-thread-factory]
  (-> (BasicThreadFactory$Builder.)
      (.wrappedFactory pool-thread-factory)
      (.namingPattern threadpool-name-pattern)
      (.uncaughtExceptionHandler logging-exception-handler)
      (.daemon true)
      .build))

(defn shutdown
  "Shuts down the gated threadpool, ensuring in-flight work is
  complete before tearing it down. Waits up to `shutdown-timeout` (in
  milliseconds) for that work to complete before forcibly shutting
  down the threadpool that work to complete"
  [{:keys [^Semaphore semaphore ^ExecutorService threadpool shutdown-timeout]}]

  ;; Prevent new work from being accepted
  (.drainPermits semaphore)

  ;; Shutdown the threadpool, allowing in-flight work to finish
  (.shutdown threadpool)

  ;; This will block, waiting for the threadpool to shutdown
  (when-not (.awaitTermination threadpool shutdown-timeout java.util.concurrent.TimeUnit/MILLISECONDS)

    (log/warn
     (i18n/trs "Threadpool not stopped after {0} milliseconds, forcibly shutting it down" shutdown-timeout))

    ;; This will force the shutdown of the threadpool and will
    ;; not allow current threads to finish
    (.shutdownNow threadpool)
    (log/warn
     (i18n/trs "Threadpool forcibly shutdown"))))

(defrecord GatedThreadpool [^Semaphore semaphore ^ExecutorService threadpool shutdown-timeout]
  java.io.Closeable
  (close [this]
    (shutdown this)))

(defn create-threadpool
  "Creates an unbounded threadpool with the intent that access to the
  threadpool is bounded by the semaphore. Implicitly the threadpool is
  bounded by `size`, but since the semaphore is handling that aspect,
  it's more efficient to use an unbounded pool and not duplicate the
  constraint in both the semaphore and the threadpool"
  [size name-pattern shutdown-timeout-in-ms]
  (let [threadpool (ThreadPoolExecutor. 1
                                        Integer/MAX_VALUE
                                        1
                                        TimeUnit/MINUTES
                                        (SynchronousQueue.))]
    (->> threadpool
         .getThreadFactory
         (thread-factory name-pattern)
         (.setThreadFactory threadpool))

    (map->GatedThreadpool
     {:semaphore (Semaphore. size)
      :threadpool threadpool
      :shutdown-timeout shutdown-timeout-in-ms})))

(defn call-on-threadpool
  "Executes `f` on a gated threadpool. Calls `on-complete` with the
  results of inoking `f`"
  [{:keys [^Semaphore semaphore ^ExecutorService threadpool] :as gated-threadpool}
   f]
  (.acquire semaphore)
  (try
    (.execute threadpool (fn []
                           (try
                             (f)
                             (finally
                               (.release semaphore)))))
    (catch RejectedExecutionException e
      (log/error e (i18n/trs "Message not submitted to command processing threadpool"))
      (.release semaphore))))

(defn exec-from-channel
  "Executes `on-input` for each input found on `in-chan`, with a
  threadpool of size `parallelism`. The results of the `on-input`
  invocations will be put to the `out-chan` in the order it has
  completed"
  [gated-threadpool in-chan on-input]
  (loop [cmd (async/<!! in-chan)]
    (when cmd
      (call-on-threadpool
       gated-threadpool
       (fn []
         (on-input cmd)))
      (recur (async/<!! in-chan)))))





