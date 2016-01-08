(ns puppetlabs.puppetdb.utils.metrics
  (:require [metrics.timers :refer [time!]]))

;;; Metrics and timing

(defn multitime!*
  "Helper for `multitime!`. Given a set of timer objects and a
  function, wrap the function in nested calls to `time!` so that
  execution of the function has its execution time tracked in each of
  the supplied timer objects."
  [timers f]
  {:pre [(coll? timers)]}
  (let [wrapped-fn (reduce (fn [thunk timer]
                             #(time! timer (thunk)))
                           f
                           timers)]
    (wrapped-fn)))

(defmacro multitime!
  "Like `time!`, but tracks the execution time in each of the supplied
  timer objects"
  [timers & body]
  `(multitime!* ~timers (fn [] (do ~@body))))
