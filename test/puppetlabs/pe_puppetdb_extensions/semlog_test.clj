(ns puppetlabs.pe-puppetdb-extensions.semlog-test
  (:require [puppetlabs.pe-puppetdb-extensions.semlog :as semlog :refer :all]
            [puppetlabs.pe-puppetdb-extensions.semlog-protocols :refer :all]
            [clojure.test :refer :all]
            [clojure.tools.logging :as tlog]
            [clojure.tools.logging.impl :as impl]
            [puppetlabs.kitchensink.core :refer [mapkeys]])
  (:import [org.slf4j MDC]))

(def ^:private stns *ns*)

(deftest interleave-all-test
  (are [a b result] (= result (interleave-all a b))
    [] [] []
    [1] [] [1]
    [] [:a] [:a]
    [1] [:a] [1 :a]
    [1 2] [:a] [1 :a 2]
    [1] [:a :b] [1 :a :b]
    [1 2] [:a :b] [1 :a 2 :b]))

(deftest interpolate-message-test
  (are [message ctx-map result] (= result (interpolate-message message ctx-map))
    "{a}" {:a 1} "1"
    "before {a}" {:a 1} "before 1"
    "{a} after" {:a 1} "1 after"
    "before {a} after" {:a 1} "before 1 after"
    "first {a} then {b}" {:a 1, :b 2} "first 1 then 2"
    "first {a} then {b} end" {:a 1, :b 2} "first 1 then 2 end"
    "kw {key}" {:key :val} "kw :val"))

(defn make-log-record [logger-ns level ex message marker]
  (letfn [(maybe-add-exception [rec]
            (if ex (assoc rec :throwable ex) rec))
          (maybe-add-marker [rec]
            (if marker
              (assoc rec :marker marker)
              rec))]
    (-> {:ns logger-ns, :level level, :message message}
        maybe-add-exception
        maybe-add-marker)))

(defn atom-logger-factory [log-atom]
  (reify impl/LoggerFactory
    (name [_] "atomLogger")
    (get-logger [_ logger-ns]
      (reify
        impl/Logger
        (enabled? [_ _] true)
        (write! [_ level ex message]
          (swap! log-atom conj (make-log-record logger-ns level ex message nil)))
        MarkerLogger
        (write-with-marker! [logger level ex message marker]
          (swap! log-atom conj (make-log-record logger-ns level ex message marker)))))))

(defn expect-log [f expected-log]
  (let [log (atom [])]
    (binding [tlog/*logger-factory* (atom-logger-factory log)]
      (f))
    (is (= expected-log @log))))

(def throwable (Exception. "ex"))

(deftest specify-ns-explicity
  (testing "custom logp"
   (are [f expected] (expect-log f expected)
     #(logp :error "Test" 123)
     [{:ns stns, :level :error, :message "Test 123"}]

     #(logp [:sync :error] "Test" 123)
     [{:ns :sync, :level :error, :message "Test 123"}]

     #(logp [:sync :error] throwable "Test" 123)
     [{:ns :sync, :level :error, :message "Test 123", :throwable throwable}]))

  (testing "custom logf"
   (are [f expected] (expect-log f expected)
     #(logf :error "Test %s" 123)
     [{:ns stns, :level :error, :message "Test 123"}]

     #(logf [:sync :error] "Test %s" 123)
     [{:ns :sync, :level :error, :message "Test 123"}]

     #(logf [:sync :error] throwable "Test %s" 123)
     [{:ns :sync, :level :error, :message "Test 123", :throwable throwable}])))

(deftest maplog-test
  (are [f expected] (expect-log f expected)
    #(maplog :error {:key :val} "Test")
    [{:ns stns, :level :error, :message "Test", :marker {:key :val}}]

    #(maplog [:sync :error] {:key :val} "Test")
    [{:ns :sync, :level :error, :message "Test", :marker {:key :val}}]

    #(maplog [:sync :error] {:key :val} "Test, {key}")
    [{:ns :sync, :level :error, :message "Test, :val", :marker {:key :val}}]

    #(maplog [:sync :error] {:key :val} "Test, {key} %s" 42)
    [{:ns :sync, :level :error, :message "Test, :val 42", :marker {:key :val}}]))

(deftest maplog-format-escaping
  (let [ctx {:w "%foo"
             :x "%"
             :y "%%"
             :z "%%%"}]
    (expect-log #(maplog :info ctx "%s {w} {x} {y} {z}" "embedded")
                [{:ns stns :level :info
                  :message "embedded %foo % %% %%%"
                  :marker ctx}])))
