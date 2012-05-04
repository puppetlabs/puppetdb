;; ## "The Kitchen Sink"
;;
;; Pretty much everything in here should _probably_ be organized into
;; proper namespaces, or perhaps even separate libraries
;; altogether. But who has time for that?

(ns com.puppetlabs.utils
  (:import (org.ini4j Ini)
           [org.apache.log4j PropertyConfigurator])
  (:require [clojure.test]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.tools.cli :as cli]
            [cheshire.core :as json]
            [digest]
            [fs.core :as fs]
            [ring.util.response :as rr])
  (:use [clojure.core.incubator :only (-?>)]
        [clojure.java.io :only (reader)]
        [clojure.set :only (difference union)]
        [clj-time.core :only [now]]
        [clj-time.format :only [formatters unparse]]
        [slingshot.slingshot :only (try+ throw+)]))

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
  "Return map `m`, with each value transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [k (f v)]))))

(defn mapkeys
  "Return map `m`, with each key transformed by function `f`"
  [f m]
  (into {} (concat (for [[k v] m]
                     [(f k) v]))))

(defn array?
  "Returns true if `x` is an array"
  [x]
  (-?> x
       (class)
       (.isArray)))

(defn keyset
  "Retuns the set of keys from the supplied map"
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

;; ## Timestamping

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
  [{:keys [global] :as config}]
  {:pre  [(map? config)]
   :post [(map? %)]}
  (when-let [logging-conf (:logging-config global)]
    (configure-logger-via-file! logging-conf))
  config)

;; ## Command-line parsing

(defn cli!
  "Wraps `tools.cli/cli`, automatically adding in a set of options for
  displaying help.

  If the user asks for help, we display the help banner text and the
  process will immediately exit."
  [args & specs]
  (let [specs                    (conj specs
                                       ["-c" "--config" "Path to config.ini" :required true]
                                       ["-h" "--help" "Show help" :default false :flag true]
                                       ["--trace" "Print stacktraces on error" :default false :flag true])
        [options posargs banner] (apply cli/cli args specs)]
    (when (:help options)
      (println banner)
      (System/exit 0))
    [options posargs]))

;; ## Ring helpers

(defn acceptable-content-type
  "Returns a boolean indicating whether the `candidate` mime type
  matches any of those listed in `header`, an Accept header."
  [candidate header]
  {:pre [(string? candidate)]}
  (if-not (string? header)
    true
    (let [[prefix suffix] (.split candidate "/")
          wildcard        (str prefix "/*")
          types           (->> (clojure.string/split header #",")
                               (map #(.trim %))
                               (set))]
      (or (types wildcard)
          (types candidate)))))

(defn json-response
  "Returns a Ring response object with the supplied `body` and response `code`,
  and a JSON content type. If unspecified, `code` will default to 200."
  ([body]
     (json-response body 200))
  ([body code]
     (-> body
         (json/generate-string {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"})
         (rr/response)
         (rr/header "Content-Type" "application/json")
         (rr/status code))))

(defn error-response
  "Returns a Ring response object with a status code of 400. If `error` is a
  Throwable, its message is used as the body of the response. Otherwise,
  `error` itself is used."
  [error]
  (let [msg (if (instance? Throwable error)
              (.getMessage error)
              (str error))]
    (-> msg
        (rr/response)
        (rr/status 400))))

(defn uri-segments
  "Converts the given URI into a seq of path segments. Empty segments
  (from a `//`, for example) are elided from the result"
  [uri]
  (remove #{""} (.split uri "/")))

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

;; UUID handling

(defn uuid
  "Generate a random UUID and return its string representation"
  []
  (str (java.util.UUID/randomUUID)))

;; System interface

(defn num-cpus
  "Grabs the number of available CPUs for the local host"
  []
  {:post [(pos? %)]}
  (-> (Runtime/getRuntime)
      (.availableProcessors)))
