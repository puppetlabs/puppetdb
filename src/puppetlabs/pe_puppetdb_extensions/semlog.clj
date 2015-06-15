(ns puppetlabs.pe-puppetdb-extensions.semlog
  (:require [clojure.tools.logging :as tlog]
            [clojure.tools.logging.impl :as impl]
            [io.clj.logging :refer [with-logging-context]]
            [clojure.core.match :as cm]
            [puppetlabs.kitchensink.core :refer [mapvals]]))

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

(defn maplog' [logger ns level throwable-or-ctx ctx-or-message & more]
  (let [[throwable ctx msg fmt-args]
        (if (instance? Throwable throwable-or-ctx)
          [throwable-or-ctx ctx-or-message (first more) (rest more)]
          [nil throwable-or-ctx ctx-or-message more])
        esc-ctx (mapvals #(.replace (str %) "%" "%%") ctx)]
    (with-logging-context ctx
      (tlog/log* logger level throwable
                 (apply format (interpolate-message msg esc-ctx) fmt-args)))))

(defmacro maplog
  "Log, as data, the map `ctx-map`. This will be made available to slf4j as the
  'mapped diagnostic context', or MDC. It may be included in a logback message
  using the '%mdc' formatter.

  Note that the MDC is a string->string map; you can provide anything you like
  as map entries, but the keys will be passed through `name` and the values
  through `str`.

  The message parameter is formatted first with string interpolation, using the
  `interpolate-message` function against `ctx-map`. This should be sufficient
  for most cases. But if needed, the result is then passed to
  `clojure.core/format` with the remaining arguments.

  The `level-or-pair` parameter may be either a log level keyword like `:error`
  or a vector of a custom logger and the log level, like `[:sync :error]`.

  Examples:

  `(maplog :info {:status 200} \"Received success status {status}\")`

  `(maplog [:sync :warn] {:remote ..., :response ...}
           \"Failed to pull record from remote {remote}. Response: status {status}\")`

  `(maplog [:sync :info] {:remote ...}
           \"Finished pull from {remote} in %s seconds\" sync-time)`"

  {:arglists '([level-or-pair ctx-map message & format-args]
               [level-or-pair throwable ctx-map message & format-args])}
  [level-or-pair x y & more]
  `(let [lop# ~level-or-pair
         [ns# level#] (if (coll? lop#) lop# [~*ns* lop#])
         logger# (impl/get-logger tlog/*logger-factory* ns#)]
     (when (impl/enabled? logger# level#)
       (maplog' logger# ns# level# ~x ~y ~@more))))
