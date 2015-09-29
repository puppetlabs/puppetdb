(ns puppetlabs.pe-puppetdb-extensions.sync.events
  (:require [puppetlabs.structured-logging.core :refer [maplog]]
            [puppetlabs.kitchensink.core :as ks]
            [clj-time.core :as time :refer [now interval in-seconds]]
            [metrics.gauges :refer [gauge-fn]]
            [metrics.counters :refer [counter] :as counters]
            [metrics.timers :refer [timer time-fn!]]))

;;; Metrics

;; The metrics framework will store state for timers and counters, but not
;; gauges; this atom is used either directly for the gauge metrics or to compute
;; their state.
(def ^:private metrics-backing-state
  (atom {:last-sync-succeeded false
         :sync-has-worked-once false
         :sync-has-failed-after-working false
         :last-successful-sync-time nil
         :last-failed-sync-time nil}))

(defn- seconds-since-last-successful-sync []
  (-> (@metrics-backing-state :last-successful-sync-time)
      (interval (now))
      in-seconds))

(defn- seconds-since-last-failed-sync []
  (-> (@metrics-backing-state :last-failed-sync-time)
      (interval (now))
      in-seconds))

(defn- metric-name [name]
  ["puppetlabs.pe-puppetdb-extensions.sync" "default" name])

(defn- make-gauge
  "Create a gauge that just pulls a value out of the metrics-backing-state
  atom."
  [kw]
  (gauge-fn (metric-name (name kw))
            #(kw @metrics-backing-state)))

;; The actual metrics objects; calling functions in the metrics.* namespaces
;; causes the data to be available in jmx and via the metrics endpoint. It's not
;; strictly necessary to hold onto references to all of these, but we do so for
;; general cleanliness.
(def ^:private metrics
  {:last-sync-succeeded (make-gauge :last-sync-succeeded)
   :sync-has-worked-once (make-gauge :sync-has-worked-once)
   :sync-has-failed-after-working (make-gauge :sync-has-failed-after-working)
   :last-successful-sync-time (make-gauge :last-successful-sync-time)
   :last-failed-sync-time (make-gauge :last-failed-sync-time)
   :seconds-since-last-successful-sync (gauge-fn (metric-name "seconds-since-last-successful-sync")
                                                 seconds-since-last-successful-sync)
   :seconds-since-last-failed-sync (gauge-fn (metric-name "seconds-since-last-failed-sync")
                                             seconds-since-last-failed-sync)
   :failed-request-counter (counter (metric-name "failed-requests"))
   :timers {"sync" (timer (metric-name "sync-duration"))
            "record" (timer (metric-name "record-transfer-duration"))
            ["entity" "catalogs"] (timer (metric-name "catalogs-sync-duration"))
            ["entity" "reports"] (timer (metric-name "reports-sync-duration"))
            ["entity" "factsets"] (timer (metric-name "factsets-sync-duration"))
            ["entity" "nodes"] (timer (metric-name "nodes-sync-duration"))}})

(defn- with-timer-metric
  "Time the execution of the function 'f' and publish it in the timer metric
  indicated by 'key'. This is one of \"sync\", \"record\", or [\"entity\"
  some-entity-name], corresponding to the sync phases we care to time."
  [key f]
  (if-let [timer (get-in metrics [:timers key])]
    (time-fn! timer f)
    (f)))


;;; Event logging

(defn now-ms [] (System/currentTimeMillis))

(defn- maybe-assoc-ok [context event]
  (case event
    :start context
    :finished (assoc context :ok true)
    :error (assoc context :ok false)))

(defn- resolve-thunk [x]
  (if (fn? x)
    (x)
    x))

(defn- generic-mapvals [f m]
  (into (empty m) (for [[k v] m] [k (f v)])))

(defn sync-event [event {:keys [context] :as opts}]
  (let [[level message] (get opts event [:debug nil])
        context (-> (generic-mapvals resolve-thunk
                                     (into (sorted-map) context))
                    (assoc :event (name event))
                    (maybe-assoc-ok event))]
    (when message
     (if-let [ex (:exception context)]
       (maplog [:sync level] ex (dissoc context :exception) message)
       (maplog [:sync level] context message)))))

(defn timer-metric-key [opts]
  (let [key-fn (get opts :timer-key :phase)]
    (key-fn (:context opts))))

(defn with-sync-events-fn [opts f]
  (with-timer-metric (timer-metric-key opts)
    (fn []
      (let [start-time (now-ms)]
        (try
          (sync-event :start opts)
          (let [return-value (f)]
            (sync-event :finished (update opts :context
                                          assoc :elapsed (- (now-ms) start-time)))
            return-value)
          (catch Exception ex
            (sync-event :error (update opts :context
                                       merge {:elapsed (- (now-ms) start-time)
                                              :exception ex}))
            (throw ex)))))))


;;; Public api

(defn failed-request!
  "A request failed! Update the metrics state to reflect this."
  []
  (counters/inc! (:failed-request-counter metrics)))

(defn successful-sync!
  "A sync succeeded! Update metrics state to reflect this."
  []
  (swap! metrics-backing-state
         assoc
         :last-sync-succeeded true
         :sync-has-worked-once true
         :last-successful-sync-time (now)))

(defn failed-sync!
  "A sync failed! Update the metrics state to reflect this."
  []
  (swap! metrics-backing-state
         (fn [s]
           (assoc s
                  :last-sync-succeeded false
                  :sync-has-failed-after-working (:sync-has-worked-once s)
                  :last-failed-sync-time (now)))))

(defmacro with-sync-events
  "Surround the given code block with logging and events. The options map should be
  of the form:

      {:context {:phase \"somePhase\"
                 :my-data :my-val
                 :computed-data #(+ 1 2)}
       :start [:info \"Started.\"]
       :finished [:info \"Finished.\"]
       :error [:warn \"Error; the value was {my-data}.\"]
       :timer-key :my-data}

  The context map and log messages will be passed to maplog. If you provide a
  function of no arguments (a thunk) as any of the context map values, it will
  be evaluated before passing it to the log functions. This is useful, for
  example, if you need to log the contents of an atom that is different before
  and after the body executes.

  You may optionally provide a :timer-key, a function of the context map whose
  output is used to determine which timer metric to use.

  All messages will be logged to the :sync logger."
  [opts & body]
  `(with-sync-events-fn ~opts #(do ~@body)))
