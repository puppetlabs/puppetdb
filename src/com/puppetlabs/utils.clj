;; ## "The Kitchen Sink"
;;
;; Pretty much everything in here should _probably_ be organized into
;; proper namespaces, or perhaps even separate libraries
;; altogether. But who has time for that?

(ns com.puppetlabs.utils
  (:import [org.ini4j Ini]
           [org.apache.log4j PropertyConfigurator]
           [org.apache.log4j ConsoleAppender]
           [org.apache.log4j PatternLayout]
           [org.apache.log4j Logger]
           [org.apache.log4j Level]
           [javax.naming.ldap LdapName])
  (:require [clojure.test]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [digest]
            [fs.core :as fs])
  (:use [clojure.core.incubator :only (-?> -?>>)]
        [clojure.java.io :only (reader)]
        [clojure.set :only (difference union)]
        [clojure.stacktrace :only (print-cause-trace)]
        [clj-time.core :only [now]]
        [clj-time.coerce :only [ICoerce to-date-time]]
        [clj-time.format :only [formatters unparse]]
        [slingshot.slingshot :only (try+ throw+)]))

;; ## Type checking

(defn array?
  "Returns true if `x` is an array"
  [x]
  (-?> x
    (class)
    (.isArray)))

(defn datetime?
  "Predicate returning whether or not the supplied object is
  convertible to a Joda DateTime"
  [x]
  (and
    (satisfies? ICoerce x)
    (to-date-time x)))

;; ## I/O

(defn lines
  "Returns a sequence of lines from the given filename"
  [filename]
  (-> filename
      (fs/file)
      (reader)
      (line-seq)))

;; ## Math

(defn quotient
  "Performs division on the supplied arguments, substituting `default`
  when the divisor is 0"
  ([dividend divisor]
     (quotient dividend divisor 0))
  ([dividend divisor default]
     (if (zero? divisor)
       default
       (/ dividend divisor))))

;; ## Numerics

(defn parse-int
  "Parse a string `s` as an integer, returning nil if the string doesn't
  contain an integer."
  [s]
  {:pre  [(string? s)]
   :post [(or (integer? %) (nil? %))]}
  (try (Integer/parseInt s)
       (catch java.lang.NumberFormatException e
         nil)))

(defn parse-float
  "Parse a string `s` as a float, returning nil if the string doesn't
  contain a float"
  [s]
  {:pre  [(string? s)]
   :post [(or (float? %) (nil? %))]}
  (try (Float/parseFloat s)
       (catch java.lang.NumberFormatException e
         nil)))

(defn parse-number
  "Converts a string `s` to a number, by attempting to parse it as an integer
  and then as a float. Returns nil if the string isn't numeric."
  [s]
  {:pre  [(string? s)]
   :post [(or (number? %) (nil? %))]}
  ((some-fn parse-int parse-float) s))

;; ## Concurrency primitives

(defn swap-and-return-old-val!
  ;; TODO: docs
  [a f & args]
  (loop []
    (let [old-value @a
          new-value (apply f old-value args)]
      (if (compare-and-set! a old-value new-value)
        old-value
        (recur)))))

;; ## Collection operations

(defn symmetric-difference
  "Computes the symmetric difference between 2 sets"
  [s1 s2]
  (union (difference s1 s2) (difference s2 s1)))

(defn as-collection
  "Returns the item wrapped in a collection, if it's not one
  already. Returns a list by default, or you can use a constructor func
  as the second arg."
  ([item]
     (as-collection item list))
  ([item constructor]
     {:post [(coll? %)]}
     (if (coll? item)
       item
       (constructor item))))

(defn mapvals
  "Return map `m`, with each value transformed by function `f`.

  You may also provide an optional list of keys `ks`; if provided, only the
  specified keys will be modified."
  ([f m]
    (into {} (for [[k v] m] [k (f v)])))
  ([f ks m]
    ;; would prefer to share code between the two implementations here, but
    ;; the `into` is much faster for the base case and the reduce is much
    ;; faster for any case where we're operating on a subset of the keys.
    ;; It seems like `select-keys` is fairly expensive.
    (reduce (fn [m k] (update-in m [k] f)) m ks)))

(defn mapkeys
  "Return map `m`, with each key transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [(f k) v]))))

(defn maptrans
  "Return map `m`, with values transformed according to the key-to-function
  mappings specified in `keys-fns`.  `keys-fns` should be a map whose keys
  are lists of keys from `m`, and whose values are functions to apply to those
  keys.

  Example: `(maptrans {[:a, :b] inc [:c] dec} {:a 1 :b 1 :c 1})` yields `{:a 2, :c 0, :b 2}`"
  [keys-fns m]
  {:pre [(map? keys-fns)
         (every? (fn [[ks fn]] (and (coll? ks) (ifn? fn))) keys-fns)
         (map? m)]}
  (let [ks (keys keys-fns)]
    (reduce (fn [m k] (mapvals (keys-fns k) k m)) m ks)))

(defn dissoc-if-nil
  "Given a map and a key, checks to see if the value for the key is `nil`; if so,
  returns a modified map with the specified key removed.  If the value is not `nil`,
  simply returns the original map."
  ([m k]
    {:pre  [(map? m)]
     :post [(map? %)]}
    (if (nil? (m k))
      (dissoc m k)
      m))
  ([m k & ks]
     (let [ret (dissoc-if-nil m k)]
       (if ks
         (recur ret (first ks) (next ks))
         ret))))

(defn keyset
  "Returns the set of keys from the supplied map"
  [m]
  {:pre  [(map? m)]
   :post [(set? %)]}
  (set (keys m)))

(defn valset
  "Returns the set of values from the supplied map"
  [m]
  {:pre  [(map? m)]
   :post [(set? %)]}
  (set (vals m)))


(defn iterator-fn->lazy-seq
  ;; TODO docs
  [f]
  {:pre  [(fn? f)]
   :post [(seq? %)]}
  (lazy-seq
    (let [next-item (f)]
      (if (nil? next-item)
        '()
        (cons next-item (iterator-fn->lazy-seq f))))))


;; ## Date and Time

(defn timestamp
  "Returns a timestamp string for the given `time`, or the current time if none
  is provided. The format of the timestamp is eg. 2012-02-23T22:01:39.539Z."
  ([]
     (timestamp (now)))
  ([time]
     (unparse (formatters :date-time) time)))

;; ## Exception handling

(defn keep-going*
  "Executes the supplied fn repeatedly. Execution may be stopped with an
  InterruptedException."
  [f on-error]
  (if (try
        (f)
        true
        (catch InterruptedException e
          false)
        (catch Throwable e
          (on-error e)
          true))
    (recur f on-error)))

(defmacro keep-going
  "Executes body, repeating the execution of body even if an exception
  is thrown"
  [on-error & body]
  `(keep-going* (fn [] ~@body) ~on-error))

(defmacro with-error-delivery
  "Executes body, and delivers an exception to the provided promise if one is
  thrown."
  [error & body]
  `(try
     ~@body
     (catch Throwable e#
       (deliver ~error e#))))

;; ## Unit testing

;; This is an implementation of assert-expr that works with
;; slingshot-based exceptions, so you can do:
;;
;;     (is (thrown+? <some exception> (...)))
(defmethod clojure.test/assert-expr 'thrown+? [msg form]
  (let [klass (second form)
        body  (nthnext form 2)]
    `(try+ ~@body
           (clojure.test/do-report {:type :fail, :message ~msg,
                                    :expected '~form, :actual nil})
           (catch ~klass e#
             (clojure.test/do-report {:type :pass, :message ~msg,
                                      :expected '~form, :actual e#})
             e#))))

;; ## Configuration files

(defn ini-to-map
  "Takes a .ini filename and returns a nested map of
  fully-interpolated values. Strings that look like integers are
  returned as integers, and all section names and keys are returned as
  symbols."
  [filename]
  {:pre  [(or (string? filename)
              (instance? java.io.File filename))]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? map? (vals %))]}
  (let [ini        (Ini. (reader filename))
        m          (atom {})
        keywordize #(keyword (string/lower-case %))]

    (doseq [[name section] ini
            [key _] section
            :let [val (.fetch section key)
                  val (or (parse-int val) val)]]
      (swap! m assoc-in [(keywordize name) (keywordize key)] val))
    @m))

(defn inis-to-map
  "Takes a path and converts the pointed-at .ini files into a nested
  map (see `ini-to-map` for details). If `path` is a file, the
  behavior is exactly the same as `ini-to-map`. If `path` is a
  directory, we return a merged version of parsing all the .ini files
  in the directory (we do not do a recursive find of .ini files)."
  ([path]
     (inis-to-map path "*.ini"))
  ([path glob-pattern]
     {:pre  [(or (string? path)
                 (instance? java.io.File path))]
      :post [(map? %)]}
     (let [files (if-not (fs/directory? path)
                   [path]
                   (fs/glob (fs/file path glob-pattern)))]
       (->> files
            (sort)
            (map fs/absolute-path)
            (map ini-to-map)
            (apply merge)
            (merge {})))))

;; ## Logging helpers

(defn catch-all-logger
  "A logging function useful for catch-all purposes, that is, to
  ensure that a log message gets in front of a user the best we can
  even if that means duplicated output.

  This is really only suitable for _last-ditch_ exception handling,
  where we want to make sure an exception is logged (because nobody
  higher up in the stack will log it for us)."
  ([exception]
     (catch-all-logger exception "Uncaught exception"))
  ([exception message]
     (print-cause-trace exception)
     (flush)
     (log/error exception message)))

(defn set-default-uncaught-exception-handler!
  "Sets the JVM global handler for uncaught exceptions to the supplied
  function.

  `f` is a function that takes 2 arguments (the Thread object involved
  in the exception, and the Exception itself). The return value is
  ignored.

  If `f` isn't supplied, we default to using
  `com.puppetlabs.utils/catch-all-logger`.

  If a default handler is already defined, we throw an exception."
  ([]
     (set-default-uncaught-exception-handler!
      (fn [_ e]
        (catch-all-logger e))))
  ([f]
     {:pre [(fn? f)]}
     (when (Thread/getDefaultUncaughtExceptionHandler)
       (throw (IllegalStateException. "Default handler already defined; won't override")))

     (let [handler (proxy [Thread$UncaughtExceptionHandler] []
                     (uncaughtException [thread exception]
                       (f thread exception)))]
       (Thread/setDefaultUncaughtExceptionHandler handler))))

(defn add-shutdown-hook!
  "Adds a shutdown hook to the JVM runtime.

  `f` is a function that takes 0 arguments; the return value is ignored.  This
  function will be called if the JVM receiveds an interrupt signal (e.g. from
  `kill` or CTRL-C); you can use it to log shutdown messages, handle state
  cleanup, etc."
  [f]
  {:pre [(fn? f)]}
  (.addShutdownHook (Runtime/getRuntime) (Thread. f)))

(defn create-console-appender
  "Instantiates and returns a logging appender configured to write to
  the console, using the standard puppetdb logging configuration.

  `level` is an optional argument (of type `org.apache.log4j.Level`)
  indicating the logging threshold for the new appender.  Defaults
  to `DEBUG`."
  ([]
     (create-console-appender Level/DEBUG))
  ([level]
     {:pre [(instance? Level level)]}
     (let [layout (PatternLayout. "%d %-5p [%t] [%c{2}] %m%n")]
       (doto (ConsoleAppender.)
         (.setLayout layout)
         (.setThreshold level)
         (.activateOptions)))))

(defn add-console-logger!
  "Adds a console logger to the current logging configuration, and ensures
  that the root logger is set to log at the logging level of the new
  logger or finer.

  `level` is an optional argument (of type `org.apache.log4j.Level`)
  indicating the logging threshold for the new logger.  Defaults
  to `DEBUG`."
  ([]
     (add-console-logger! Level/DEBUG))
  ([level]
     {:pre [(instance? Level level)]}
     (let [root-logger (Logger/getRootLogger)]
       (.addAppender root-logger (create-console-appender level))
       (if (> (.toInt (.getLevel root-logger))
              (.toInt level))
         (.setLevel root-logger level)))))

(defn configure-logger-via-file!
  "Reconfigures the current logger based on the supplied configuration
  file. You can optionally supply a delay (in millis) that governs how
  often we'll check the config file for updates, and thus reconfigure
  the logger live."
  ([logging-conf-file]
     {:pre [(string? logging-conf-file)]}
     (configure-logger-via-file! logging-conf-file 10000))
  ([logging-conf-file reload-interval]
     {:pre [(string? logging-conf-file)
            (number? reload-interval)
            (pos? reload-interval)]}
     (PropertyConfigurator/configureAndWatch logging-conf-file reload-interval)))

(defn configure-logging!
  "If there is a logging configuration directive in the supplied
  config map, use it to configure the default logger. Returns the same
  config map that was passed in."
  [{:keys [global debug] :as config}]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (when-let [logging-conf (:logging-config global)]
    (configure-logger-via-file! logging-conf))
  (when debug
    (add-console-logger! Level/DEBUG)
    (log/debug "Debug logging enabled"))
  config)

(defmacro demarcate
  "Executes `body`, but logs `msg` to info before and after `body` is
  executed. `body` is executed in an implicit do, and the last
  expression's return value is returned by `demarcate`.

    user> (demarcate \"reticulating splines\" (+ 1 2 3))
    \"Starting reticulating splines\"
    \"Finished reticulating splines\"
    6
  "
  [msg & body]
  `(do (log/info (str "Starting " ~msg))
       (let [result# (do ~@body)]
         (log/info (str "Finished " ~msg))
         result#)))

;; ## Command-line parsing

(defn cli!
  "Validates that required command-line arguments are present.  If they are not,
  exits with an error and displays usage information.  Input:

  - args     : the command line arguments passed in by the user
  - specs    : an array of supported argument specifications, as accepted by
               `clojure.tools.cli`
  - required : an array of keywords (using the long form of the argument spec)
               specifying which of the `specs` are required.  If any of the
               `required` options are not present, the function will cause
               the program to exit and display the help message.

  Also checks to see whether user has passed the `--help` flag, and if so, displays
  the help and exits."
  [args specs required-args]
  (let [specs                   (conj specs
                                  ["-h" "--help" "Show help" :default false :flag true])
        [options extras banner] (apply cli/cli args specs)]
    (when (:help options)
      (println banner)
      (System/exit 0))
    (let [required-pairs (select-keys options required-args)]
      (when-let [missing-field (some #(if (nil? (val %)) (key %)) required-pairs)]
        (println)
        (println (format "Missing required argument '--%s'!" (name missing-field)))
        (println)
        (println banner)
        (System/exit 1)))
    [options extras]))


;; ## SSL Certificate handling

(defn cn-for-dn
  "Extracts the CN (common name) from an LDAP DN (distinguished name).

  If more than one CN entry exists in the given DN, we return the most-specific
  one (the one that comes last, textually). If no CN is present in the DN, we
  return nil.

  Example:

      (cn-for-dn \"CN=foo.bar.com,OU=meh,C=us\")
      \"foo.bar.com\"

      (cn-for-dn \"CN=foo.bar.com,CN=baz.goo.com,OU=meh,C=us\")
      \"baz.goo.com\"

      (cn-for-dn \"OU=meh,C=us\")
      nil"
  [dn]
  {:pre [(string? dn)]}
  (-?>> dn
        (LdapName.)
        (.getRdns)
        (filter #(= "CN" (.getType %)))
        (first)
        (.getValue)
        (str)))

(defn cn-for-cert
  "Extract the CN from the DN of an x509 certificate. See `cn-for-dn` for details
  on how extraction is performed.

  If no CN exists in the certificate DN, nil is returned."
  [^java.security.cert.X509Certificate cert]
  (-> cert
      (.getSubjectDN)
      (.getName)
      (cn-for-dn)))

;; ## Ring helpers

(defn cn-whitelist->authorizer
  "Given a 'whitelist' file containing allowed CNs (one per line),
   build a function that takes a Ring request and returns true if the
   CN contained in the client certificate appears in the whitelist.

   `whitelist` can be either a local filename or a File object.

   This makes use of the `:ssl-client-cn` request parameter. See
   `com.puppetlabs.middleware/wrap-with-certificate-cn`."
  [whitelist]
  {:pre  [(or (string? whitelist)
              (instance? java.io.File whitelist))]
   :post [(fn? %)]}
  (let [allowed? (set (lines whitelist))]
    (fn [{:keys [ssl-client-cn scheme] :as req}]
      (or (= scheme :http)
          (allowed? ssl-client-cn)))))

;; ## Hashing

;; This method lookup is surprisingly expensive (on the order of 10x slower),
;; so we pay the cost once, and define our own digest in terms of it.
(let [digest-func (get-method digest/digest :default)]
  (defn utf8-string->sha1
    "Compute a SHA-1 hash for the UTF-8 encoded version of the supplied
    string"
    [s]
    {:pre  [(string? s)]
     :post [(string? %)]}
    (let [bytes (.getBytes s "UTF-8")]
      (digest-func "sha-1" [bytes]))))

(defn bounded-memoize
  "Similar to memoize, but the cache will be reset if the number of entries
  exceeds the specified `bound`."
  [f bound]
  {:pre [(integer? bound)
         (pos? bound)]}
  (let [cache (atom {})]
    (fn [& args]
      (if-let [e (find @cache args)]
        (val e)
        (let [v (apply f args)]
          (when (> (count @cache) bound)
            (reset! cache {}))
          (swap! cache assoc args v)
          v)))))

;; ## UUID handling

(defn uuid
  "Generate a random UUID and return its string representation"
  []
  (str (java.util.UUID/randomUUID)))

;; ## System interface

(defn num-cpus
  "Grabs the number of available CPUs for the local host"
  []
  {:post [(pos? %)]}
  (.availableProcessors (Runtime/getRuntime)))


