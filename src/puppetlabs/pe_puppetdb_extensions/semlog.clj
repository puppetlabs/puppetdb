(ns puppetlabs.pe-puppetdb-extensions.semlog
  (:require [clojure.tools.logging :as tlog]
            [clojure.tools.logging.impl :as impl]
            [io.clj.logging :refer [with-logging-context]]
            [clojure.core.match :as cm]
            [puppetlabs.kitchensink.core :refer [mapvals]]
            [puppetlabs.pe-puppetdb-extensions.semlog-protocols :refer :all]
            [cheshire.core :as cheshire])
  (:import [net.logstash.logback.marker Markers LogstashMarker]
           [com.fasterxml.jackson.core JsonGenerator]))


(defmacro logp
  "This is just like `clojure.core.logging/logp`, except it accepts
  a [:logger :level] form as the first argument."
  {:arglists '([level-or-pair message & more]
               [level-or-pair throwable message & more])}
  [level-or-pair x & more]
  (if (or (instance? String x) (nil? more)) ; optimize for common case
    `(let [lop# ~level-or-pair
           [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])]
       (tlog/log ns# level# nil (print-str ~x ~@more)))
    `(let [lop# ~level-or-pair
           [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])
           logger# (impl/get-logger tlog/*logger-factory* ns#)]
       (if (impl/enabled? logger# level#)
         (let [x# ~x]
           (if (instance? Throwable x#) ; type check only when enabled
             (tlog/log* logger# level# x# (print-str ~@more))
             (tlog/log* logger# level# nil (print-str x# ~@more))))))))

(defmacro logf
  "This is just like `clojure.core.logging/logf`, except it accepts
  a [:logger :level] form as the first argument."
  {:arglists '([level-or-pair fmt & fmt-args]
               [level-or-pair throwable fmt & fmt-args])}
  [level-or-pair x & more]
  (if (or (instance? String x) (nil? more)) ; optimize for common case
    `(let [lop# ~level-or-pair
           [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])]
       (tlog/log ns# level# nil (format ~x ~@more)))
    `(let [lop# ~level-or-pair
           [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])
           logger# (impl/get-logger tlog/*logger-factory* ns#)]
       (if (impl/enabled? logger# level#)
         (let [x# ~x]
           (if (instance? Throwable x#) ; type check only when enabled
             (tlog/log* logger# level# x# (format ~@more))
             (tlog/log* logger# level# nil (format x# ~@more))))))))

(defn interleave-all
  ([s t] (interleave-all s t true))
  ([s t take-from-s]
   (lazy-seq (cond
               (and (seq s) take-from-s)
               (cons (first s) (interleave-all (rest s) t false))

               (and (seq t) (not take-from-s))
               (cons (first t) (interleave-all s (rest t) true))

               (and (not (seq s)) take-from-s)
               t

               (and (not (seq t)) (not take-from-s))
               s))))

(defn interpolate-message
  "Ruby-ish string interpolation, using {} as delimiter characters in the
  `message` string and `ctx-map` as the data source, a map with keywords as
  keys.

  For example:

  `(interpolate-message \"{animal} is eating his good {meal}!\"
                        {:animal \"Bunny\" :meal \"supper\"}`"
  [message ctx-map]
  {:pre [(every? keyword? (keys ctx-map))]}
  (let [pat (re-pattern "\\{\\w+\\}")
        in-between-text (clojure.string/split message pat)
        replacements (->> (re-seq pat message)
                          (map #(subs % 1 (dec (count %))))
                          (map #(get ctx-map (keyword %))))]
    (apply str (interleave-all in-between-text replacements))))


(definterface ISemlogMarker
  (semlogMap [] "Returns the semlog map for this marker."))

(defn- merge-clojure-map-marker
  "Create a marker that, when written to the LogStash json encoder, will
  json-encode the given map `m` and merge it with any already-created json.

  Use the following encoder configuration inside your logback appender
  configuration to write your log messages as json:

    <encoder class=\"net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder\">
      <providers>
        <timestamp/>
        <message/>
        <loggerName/>
        <threadName/>
        <logLevel/>
        <logLevelValue/>
        <stackTrace/>
        <logstashMarkers/>
      </providers>
    </encoder>
  "
  [m]
  (proxy [LogstashMarker ISemlogMarker] ["SEMLOG_MAP"]
    (writeTo [^JsonGenerator generator]
      (binding [cheshire/*generator* generator]
        ;; `::none` is the 'wholeness' parameter to cheshire, which indicates which
        ;; start- and end-object markers to write. In this case we don't want any
        ;; of them, so we'll pass `::none`. This is not on the list of supported values,
        ;; but the fact that it's not equal to any of them means it will work.
        (cheshire/write m ::none)))
    (semlogMap [] m)))

(extend-protocol MarkerLogger
  org.slf4j.Logger
  (write-with-marker! [logger level e msg marker-map]
    (let [^String msg (str msg)
          marker (merge-clojure-map-marker marker-map)]
      (if e
        (case level
          :trace (.trace logger marker msg e)
          :debug (.debug logger marker msg e)
          :info  (.info  logger marker msg e)
          :warn  (.warn  logger marker msg e)
          :error (.error logger marker msg e)
          :fatal (.error logger marker msg e)
          (throw (IllegalArgumentException. (str level))))
        (case level
          :trace (.trace logger marker msg)
          :debug (.debug logger marker msg)
          :info  (.info  logger marker msg)
          :warn  (.warn  logger marker msg)
          :error (.error logger marker msg)
          :fatal (.error logger marker msg)
          (throw (IllegalArgumentException. (str level))))))))

(defn maplog' [logger ns level throwable-or-ctx ctx-or-message & more]
  (let [[throwable ctx msg fmt-args]
        (if (instance? Throwable throwable-or-ctx)
          [throwable-or-ctx ctx-or-message (first more) (rest more)]
          [nil throwable-or-ctx ctx-or-message more])
        esc-ctx (mapvals #(.replace (str %) "%" "%%") ctx)]

    (write-with-marker! logger level throwable
                        (apply format (interpolate-message msg esc-ctx) fmt-args)
                        ctx)))

(defmacro maplog
  "Logs an event after expanding any braced references to ctx-map keys
  in the message into the corresponding ctx-map values, and then
  passing the expanded string and format-args to clojure.core/format.
  Includes the ctx-map in the event as an slf4j event Marker.

  The level-or-pair parameter may be either a log level keyword like
  :error or a vector of a custom logger and the log level, like
  [:sync :error].

  Examples:

  (maplog :info {:status 200} \"Received success status {status}\")

  (maplog [:sync :warn] {:remote ..., :response ...}
           \"Failed to pull record from remote {remote}. Response: status {status}\")

  (maplog [:sync :info] {:remote ...}
           \"Finished pull from {remote} in %s seconds\" sync-time)"

  {:arglists '([level-or-pair ctx-map message & format-args]
               [level-or-pair throwable ctx-map message & format-args])}
  [level-or-pair x y & more]
  `(let [lop# ~level-or-pair
         [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])
         logger# (impl/get-logger tlog/*logger-factory* ns#)]
     (when (impl/enabled? logger# level#)
       (maplog' logger# ns# level# ~x ~y ~@more))))
