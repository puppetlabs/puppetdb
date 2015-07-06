(ns com.puppetlabs.test.concurrent
  (:use [com.puppetlabs.concurrent]
        [clojure.test])
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent Semaphore]))

(defn- create-futures
  "Helper function to create a sequence of `n` futures that simply apply function
  `f` to `args`."
  [n f & args]
  (doall (for [i (range n)] (future (apply f args)))))

(defn- increment-count
  "Test helper function: increments the `:current` counter, and if the new value
  exceeds the `:max` counter, update the `:max` counter to the new max value."
  [counts]
  {:pre [(map? counts)
         (contains? counts :current)
         (contains? counts :max)]}
  (let [updated (update-in counts [:current] inc)]
    (assoc updated :max (max (:max updated) (:current updated)))))

(defn- decrement-count
  "Test helper function: decrements the `:current` counter."
  [counts]
  {:pre [(map? counts)
         (contains? counts :current)
         (contains? counts :max)]}
  (update-in counts [:current] dec))

(defn- update-counts
  "Test helper function: calls `swap!` on the `counts` atom to increment
  the counters, sleeps for a very short period to make sure other threads have
  a change to run, and then calls `swap!` again to decrement the counters."
  [counts]
  {:pre [(instance? clojure.lang.Atom counts)]}
  (swap! counts increment-count)
  (Thread/sleep 5)
  (swap! counts decrement-count))

(defn- update-counts-and-inc
  "Test helper function: calls `update-counts` to exercise the counter/semaphore
  code, then simply calls `inc` on `item` (to allow testing results of the `map`
  operation."
  [counts item]
  {:pre [(instance? clojure.lang.Atom counts)
         (number? item)]}
  (update-counts counts)
  (inc item))

(deftest test-bound-via-semaphore
  (doseq [bound [1 2 5]]
    (testing (format "Testing bound-via-semaphore with semaphore size %s" bound)
      (let [sem     (Semaphore. bound)
            counts  (atom {:current 0 :max 0})
            futures (create-futures 10
                                    (bound-via-semaphore sem update-counts) counts)]
        (doseq [fut futures]
        ;; deref all of the futures to make sure we've waited for them to complete
          @fut)
        (is (= {:current 0 :max bound} @counts))))))

(deftest test-bounded-pmap
  (doseq [bound [1 2 5]]
    (testing (format "Testing bounded-pmap with bound %s" bound)
      (let [counts  (atom {:current 0 :max 0})
            results (doall (bounded-pmap bound (partial update-counts-and-inc counts) (range 20)))]
        (is (= {:current 0 :max bound} @counts))
        (is (= (set (map inc (range 20))) (set results)))))))
