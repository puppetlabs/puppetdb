(ns puppetlabs.puppetdb.cheshire
  "Cheshire related functions

   This front-ends the common set of core cheshire functions:

   * generate-string
   * generate-stream
   * parse-string
   * parse-stream

   This namespace when 'required' will also setup some common JSON encoders
   globally, so you can avoid doing this for each call."
  (:require [cheshire.generate :as generate]
            [cheshire.core :as core]
            [clj-time.coerce :as coerce]
            [clojure.java.io :as io]))

(defn add-common-json-encoders!*
  "Non-memoize version of add-common-json-encoders!"
  []
  (generate/add-encoder
   org.joda.time.DateTime
   (fn [data jsonGenerator]
     (.writeString jsonGenerator (coerce/to-string data)))))

(def
  ^{:doc "Registers some common encoders for cheshire JSON encoding.

  This is a memoize function, to avoid unnecessary calls to add-encoder.

  Ideally this function should be called once in your apply, for example your
  main class.

  Encoders currently include:

  * org.joda.time.DateTime - handled with to-string"}
  add-common-json-encoders! (memoize add-common-json-encoders!*))

(add-common-json-encoders!)

(def default-opts {:date-format "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"})
(def default-pretty-opts (assoc default-opts :pretty true))

(defn generate-string
  "Thinly wraps cheshire.core/generate-string, adding the PuppetDB
  default date format."
  ([obj]
   (core/generate-string obj default-opts))
  ([obj opts]
   (core/generate-string obj (merge default-opts opts))))

(defn generate-pretty-string
  "Thinly wraps cheshire.core/generate-string, adding the PuppetDB
  default date format and pretty printing from `default-pretty-opts`"
  ([obj]
   (core/generate-string obj default-pretty-opts))
  ([obj opts]
   (core/generate-string obj (merge default-pretty-opts opts))))

(defn generate-stream
  "Thinly wraps cheshire.core/generate-stream, adding the PuppetDB
  default date format."
  ([obj writer]
   (core/generate-stream obj writer default-opts))
  ([obj writer opts]
   (core/generate-stream obj writer (merge default-opts opts))))

(defn generate-pretty-stream
  "Thinly wraps cheshire.core/generate-stream, adding the PuppetDB default date format
   and pretty printing from `default-pretty-opts`"
  ([obj writer]
     (generate-pretty-stream obj writer default-pretty-opts))
  ([obj writer opts]
     (generate-stream obj writer (merge default-pretty-opts opts))))

(def parse-string core/parse-string)

(def parse-strict-string core/parse-string-strict)

(def parse-stream core/parse-stream)

(defn coerce-from-json
  "Parses as json if `s` is a string/stream/reader, otherwise return `s`"
  [obj]
  (cond
   (string? obj)
   (parse-strict-string obj true)

   (instance? java.io.Reader obj)
   (parse-stream obj true)

   (instance? java.io.InputStream obj)
   (with-open [reader (clojure.java.io/reader obj)]
     (coerce-from-json reader))

   :else
   obj))

(defn spit-json
  "Similar to clojure.core/spit, but writes the Clojure
   datastructure as JSON to `f`"
  [f obj & options]
  (with-open [writer (apply io/writer f options)]
    (generate-pretty-stream obj writer))
  nil)
