(ns com.puppetlabs.test.concurrent
  (:use clojure.test
        com.puppetlabs.concurrent
        com.puppetlabs.testutils.logging
        [com.puppetlabs.utils :only [swap-and-return-old-val!]])
  (:require [clojure.tools.logging :as log])
  (:import  [java.util.concurrent ExecutionException]))

(defn simple-work-stack
  ;; TODO docs
  [size]
  {:pre  [(pos? size)]}
  (let [original-work   (range size)
        remaining-work  (atom (range size))
        counter         (atom 0)
        iterator-fn     (fn []
      (swap! counter inc)
      (let [old-work  (swap-and-return-old-val! remaining-work next)
            next-item (first old-work)]
        next-item))]
    {:original-work   original-work
     :remaining-work  remaining-work
     :counter         counter
     :iterator-fn     iterator-fn}))

(deftest test-work-queue->seq
  (let [queue (work-queue)]
    (doseq [i (range 5)]
      (.put queue i))
    (.put queue work-queue-sentinel-complete)
    (let [queue-seq  (work-queue->seq queue)]
      (is (= (range 5) queue-seq)))))

(deftest test-producer
  (doseq [num-workers [1 2 5]
          max-work    [0 2 10]]
    (testing (format "with %d worker(s) and %d max work items" num-workers max-work)
      (let [num-work-items                5
            {:keys [counter iterator-fn
                    original-work]}       (simple-work-stack num-work-items)
            p                             (producer iterator-fn num-workers max-work)
            {:keys [workers work-queue]}  p
            queued-work                   (work-queue->seq work-queue)]
        (testing "number of workers matches what we requested"
          (is (= num-workers (count workers))))
        (testing "work queue contains the correct work items"
          (is (= (set original-work) (set queued-work))))
        (testing "workers completed the correct number of work items"
          (let [work-completed (apply + (map deref workers))]
            (is (= num-work-items work-completed))))))))

(deftest test-consumer
  (doseq [num-workers [1 2 5]
          max-work    [0 2 10]]
    (testing (format "with %d worker(s) and %d max work items" num-workers max-work)
      (let [num-work-items                    5
            {:keys [counter iterator-fn
                    original-work]}           (simple-work-stack num-work-items)
            p                                 (producer iterator-fn num-workers max-work)
            {:keys [workers result-queue]}    (consumer p inc num-workers max-work)
            queued-results                    (work-queue->seq result-queue)]
        (testing "number of workers matches what we requested"
          (is (= num-workers (count workers))))
        (testing "result queue contains the correct results"
          (is (= (set (map inc original-work)) (set queued-results))))
        (testing "workers completed the correct number of work items"
          (let [work-completed (apply + (map deref workers))]
            (is (= num-work-items work-completed))))))))

(deftest test-fail-cases
  (testing "it should fail if consumer work-fn returns nil"
    (with-log-output logs
      (let [producer-fn (constantly 1)
            work-fn     (fn [work] nil)
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalStateException (doseq [result results] nil)))
        (is (= 1 (count (logs-matching #"Something horrible happened!" @logs)))))))
  (testing "it should fail if consumer work-fn throws an exception"
    (with-log-output logs
      (let [producer-fn (constantly 1)
            work-fn     (fn [work] (throw (IllegalArgumentException. "consumer exception!")))
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalArgumentException (doseq [result results] nil)))
        (is (= 1 (count (logs-matching #"Something horrible happened!" @logs)))))))
  (testing "it should fail if producer work-fn throws an exception"
    (with-log-output logs
      (let [producer-fn (fn [] (throw (IllegalArgumentException. "producer exception!")))
            work-fn     (fn [work] 1)
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalArgumentException (doseq [result results] nil)))
        (is (= 2 (count (logs-matching #"Something horrible happened!" @logs))))))))
