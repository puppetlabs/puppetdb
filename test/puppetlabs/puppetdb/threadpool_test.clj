(ns puppetlabs.puppetdb.threadpool-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.threadpool :refer :all]))

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

      (with-open [gtp (create-threadpool 1 "test-pool-%d" 1000)]
        (let [fut (future
                    (exec-from-channel gtp in-chan worker-fn))]
          (async/>!! in-chan {:id :a})
          (is (= [:done :a] (async/<!! out-chan)))
          (is (= 1 @counter))
          (async/close! in-chan)
          (is (not= ::timed-out (deref fut 1000 ::timed-out)))
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

      (with-open [gtp (create-threadpool 1 "test-pool-%d" 1000)]
        (let [fut (future
                    (exec-from-channel gtp in-chan worker-fn))]

          (async/>!! in-chan {:foo 1})
          (async/close! in-chan)

          (is (= 0 @counter))

          (deliver stop-here true)

          (is (= [:done 1] (async/<!! out-chan)))
          
          (is (not= ::timed-out (deref fut 1000 ::timed-out)))
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
      
      (with-open [gtp (create-threadpool 2 "test-pool-%d" 1000)]
        (let [fut (future
                    (exec-from-channel gtp in-chan worker-fn))]

          (async/>!! in-chan {:id :a})
          (is (= true (deref (:a in-flight-promises) 100 ::not-found)))

          (async/>!! in-chan {:id :b})
          (is (= true (deref (:b in-flight-promises) 100 ::not-found)))

          (async/>!! in-chan {:id :c})
          (async/close! in-chan)

          (is (= ::not-found (deref (:c in-flight-promises) 100 ::not-found)))
          (is (= [:a :b] @seen))

          (deliver (get stop-here :a) true)

          (is (= [:done :a] (async/<!! out-chan)))
          (is (= true (deref (:c in-flight-promises) 100 ::not-found)))
          (is (= [:a :b :c] @seen))

          (deliver (get stop-here :b) true)
          (is (= [:done :b] (async/<!! out-chan)))

          (deliver (get stop-here :c) true)
          (is (= [:done :c] (async/<!! out-chan)))
          
          (is (not= ::timed-out (deref fut 1000 ::timed-out)))
          (is (true? (future-done? fut))))))))
