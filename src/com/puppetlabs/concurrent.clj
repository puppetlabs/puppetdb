;; ## Concurrency-related Utility Functions
;;
;; This namespace contains some utility functions for multi-thread operations.
;; In most cases these will simply be thin wrappers around clojure concurrency
;; functions and/or structures from `java.util.concurrent`.

(ns com.puppetlabs.concurrent
  (:import  [java.util.concurrent Semaphore]))

(defn bound-via-semaphore
  "Given a semaphore `sem` function `f`, return a new function which simply
  acquires the semaphore, executes `f`, and then releases the semaphore.  This
  is mostly intended to be a helper function for use by `bounded-pmap`."
  [sem f]
  {:pre [(instance? Semaphore sem)
         (ifn? f)]
   :post [(ifn? %)]}
  (fn [& args]
    (.acquire sem)
    (try
      (apply f args)
      (finally (.release sem)))))

(defn bounded-pmap
  "Similar to clojure's built-in `pmap`, but prevents concurrent evaluation of
  more than `max-threads` number of items in the resulting sequence."
  [max-threads f coll]
  {:pre [(integer? max-threads)
         (ifn? f)
         (coll? coll)]}
  (let [sem         (Semaphore. max-threads)
        bounded-fn  (bound-via-semaphore sem f)]
    (pmap bounded-fn coll)))
