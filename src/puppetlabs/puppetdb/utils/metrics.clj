(ns puppetlabs.puppetdb.utils.metrics
  (:require [metrics.timers :refer [time!]]
            [clojure.set :as set]))

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

(defn maybe-prefix-key [prefix key]
  (if prefix
    (keyword (str prefix "." (name key)))
    key))

(defn get-db-name
  "Returns a string of the db's name or nil. In command broadcast mode the db
   name is included in the hikari writepool name.
   e.g. poolname = 'PDBWritePool: foo', db name = 'foo'
   In normal mode no db specific name is present and function will return nil."
  [db]
  ;; when :datasource isn't present in test *db* map assume non-broadcast
  (some->> db
           :datasource
           .getPoolName
           (re-find #"(?<=PDBWritePool: ).*")))

(defn prefix-metric-keys
  [prefix metrics]
  (if prefix
    (->> metrics
         keys
         (map (fn [k] [k (maybe-prefix-key prefix k)]))
         (into {})
         (set/rename-keys metrics))
    metrics))
