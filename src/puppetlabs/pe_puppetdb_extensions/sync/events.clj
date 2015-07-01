(ns puppetlabs.pe-puppetdb-extensions.sync.events
  (:require [puppetlabs.pe-puppetdb-extensions.semlog :refer [maplog]]
            [puppetlabs.kitchensink.core :as ks]))

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

(defn sync-event [event {:keys [type context] :as opts}]
  (let [[level message] (get opts event)
        context (-> (generic-mapvals resolve-thunk
                                     (into (sorted-map) context))
                    (assoc :event (str (name type) "-" (name event)))
                    (maybe-assoc-ok event))]
    (if-let [ex (:exception context)]
     (maplog [:sync level] ex context message)
     (maplog [:sync level] context message))))

(defn with-sync-events-fn [opts f]
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
        (throw ex)))))

(defmacro with-sync-events
  "Surround the given code block with logging events. The options map should be
  of the form:

      {:type :some-custom-type
       :context {:my-data :my-val
                 :computed-data #(+ 1 2)}
       :start [:info \"Started.\"]
       :finished [:info \"Finished.\"]
       :error [:warn \"Error; the value was {my-data}.\"]}

  The context map and log messages will be passed to maplog. If you provide a
  function of no arguments (a thunk) as any of the context map values, it will
  be evaluated before passing it to the log functions. This is useful, for
  example, if you need to log the contents of an atom that is different before
  and after the body executes.

  All messages will be logged to the :sync logger."
  [opts & body]
  `(with-sync-events-fn ~opts #(do ~@body)))
