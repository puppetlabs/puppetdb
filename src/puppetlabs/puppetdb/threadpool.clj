(ns puppetlabs.puppetdb.threadpool
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :refer [trs tru]])
  (:import
   (java.util.concurrent Semaphore ThreadPoolExecutor TimeUnit SynchronousQueue
                         RejectedExecutionException ExecutorService)
   (org.apache.commons.lang3.concurrent BasicThreadFactory
                                        BasicThreadFactory$Builder)))

(def logging-exception-handler
  "Exception handler that ensures any uncaught exception that occurs
  on this thread is logged"
  (reify Thread$UncaughtExceptionHandler
    ;; More generic fallback in case the execution itself doesn't do anything.
    (uncaughtException [_ thread throwable]
      (let [msg (trs "Reporting unexpected error from thread {0} to stderr and log"
                     (.getName thread))]
        (binding [*out* *err*]
          (println msg)
          (println throwable))
        (log/error throwable msg)))))

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

(defn executor [core-size max-size name-pattern]
  (let [pool (ThreadPoolExecutor. core-size max-size
                                  1 TimeUnit/MINUTES
                                  (SynchronousQueue.))]
    (doto pool
      (.allowCoreThreadTimeOut true)
      (.setThreadFactory (thread-factory name-pattern (.getThreadFactory pool))))))

(defn shutdown
  "Shuts down the threadpool, ensuring in-flight work is
  complete before tearing it down. Waits up to `shutdown-timeout` (in
  milliseconds) for that work to complete before forcibly shutting
  down the threadpool"
  [{:keys [^ExecutorService threadpool shutdown-timeout]}]
  (.shutdown threadpool) ;; Initiate shutdown, allowing in-flight work to finish
  ;; This will block, waiting for the threadpool to shutdown
  (when-not (.awaitTermination threadpool shutdown-timeout TimeUnit/MILLISECONDS)
    (log/warn
     (trs "Forcing threadpool shutdown after waiting {0}ms: {1}"
          shutdown-timeout threadpool))
    (.shutdownNow threadpool) ;; Shutdown without waiting for threads to finish
    (log/warn (trs "Threadpool forcibly shut down"))))

(defn gated-execute
  "Executes `f` on `gated-threadpool`. Will throw
  RejectedExecutionException if `gated-threadpool` is being shutdown."
  [{:keys [^Semaphore semaphore ^ExecutorService threadpool]} f]
  (.acquire semaphore)
  (try
    (.execute threadpool (fn []
                           (try
                             (f)
                             (catch InterruptedException e
                               (log/debug e (trs "Thread interrupted while processing on threadpool")))
                             (finally
                               (.release semaphore)))))
    ;; RejectedExecutionExceptions only occur when attempting ot
    ;; excecute new work on a threadpool that is in the process of
    ;; shutting down or already has been shut down
    (catch RejectedExecutionException e
      (.release semaphore)
      (throw e))))

(defn shutdown-gated
  "Shuts down the gated threadpool via shutdown-pool after draining all
  the permits."
  [{:keys [^Semaphore semaphore] :as pool}]
  ;; Prevent new work from being accepted
  (.drainPermits semaphore)
  (shutdown pool))

(defrecord GatedThreadpool
    [^Semaphore semaphore ^ExecutorService threadpool shutdown-timeout]
  java.io.Closeable
  (close [this] (shutdown-gated this))
  java.util.concurrent.Executor
  (execute [this runnable] (gated-execute this runnable)))

(defn gated-threadpool
  "Creates an unbounded threadpool with the intent that access to the
  threadpool is bounded by the semaphore. Implicitly the threadpool is
  bounded by `size`, but since the semaphore is handling that aspect,
  it's more efficient to use an unbounded pool and not duplicate the
  constraint in both the semaphore and the threadpool"
  [size name-pattern shutdown-timeout-ms]
  (map->GatedThreadpool
   {:semaphore (Semaphore. size)
    :threadpool (executor 0 Integer/MAX_VALUE name-pattern)
    :shutdown-timeout shutdown-timeout-ms}))

(defn dochan
  "Calls on-input on each item found on in-chan, using the gated thread
  pool."
  [pool on-input in-chan blocked?]
  (loop [cmd (async/<!! in-chan)]
    (locking blocked? (while @blocked? (.wait blocked?)))
    (when cmd
      (try
        (.execute pool (fn [] (on-input cmd)))
        (catch RejectedExecutionException ex
          (throw (ex-info (tru "Threadpool shutting down, message rejected")
                          {:kind ::rejected :message cmd}
                          ex))))
      (recur (async/<!! in-chan)))))

(defn shutdown-unbounded [pool]
  (shutdown pool))

(defrecord UnboundedThreadpool
    [^ExecutorService threadpool shutdown-timeout]
  java.io.Closeable
  (close [this] (shutdown this))
  java.util.concurrent.Executor
  (execute [this runnable] (.execute (:threadpool this) runnable)))

(defn unbounded-threadpool
  "Creates an unbounded thread pool with PuppetDB specific adjustments,
  i.e. exception logging, etc."
  [name-pattern shutdown-timeout-ms]
  (map->UnboundedThreadpool
   {:threadpool (executor 0 Integer/MAX_VALUE name-pattern)
    :shutdown-timeout shutdown-timeout-ms}))

(defn active-count [pool]
  (.getActiveCount (:threadpool pool)))
