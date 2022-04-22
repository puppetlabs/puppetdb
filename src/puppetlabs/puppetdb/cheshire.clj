(ns puppetlabs.puppetdb.cheshire
  "Cheshire related functions

   This front-ends the common set of core cheshire functions:

   * generate-string
   * generate-stream
   * parse
   * parse-strict
   * parse-string
   * parse-stream

   This namespace when 'required' will also setup some common JSON encoders
   globally, so you can avoid doing this for each call."
  (:require [cheshire.core :as core]
            [cheshire.factory :as factory]
            [cheshire.generate :as generate
             :refer [add-encoder encode-map encode-seq]]
            [cheshire.parse :as parse]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.time :as time])
  (:import
   [com.fasterxml.jackson.core JsonGenerator]
   [com.fasterxml.jackson.core.io CharacterEscapes SerializedString]
   [java.io ByteArrayInputStream Writer]
   [org.postgresql.jdbc PgArray]
   [org.postgresql.util PGobject]))

(defrecord RawJsonString [data])

;; Alias time/to-string to avoid reflection
(def ^String to-string time/to-string)
(defn add-common-json-encoders!*
  "Non-memoize version of add-common-json-encoders!"
  []
  (add-encoder
   PgArray
   (fn [^PgArray data ^JsonGenerator generator]
     ;; If .getArray returns a copy, this approach could be expensive
     ;; for large values.
     (encode-seq (.getArray data) generator)))
  (add-encoder
    org.postgresql.util.PGobject
    (fn [^PGobject data ^JsonGenerator jsonGenerator]
      ;; The .getPrettyPrinter method on the Jackson jsonGenerator will return
      ;; nil if `:pretty` is not set as a cheshire option
      (if (.getPrettyPrinter jsonGenerator)
        (let [obj (core/parse-string (.getValue data))
              encode-fn (condp instance? obj
                          clojure.lang.IPersistentMap encode-map
                          clojure.lang.ISeq encode-seq)]
          (encode-fn obj jsonGenerator))
        (.writeRawValue jsonGenerator (.getValue data)))))
  (add-encoder
    org.joda.time.DateTime
    (fn [data ^JsonGenerator jsonGenerator]
      (.writeString jsonGenerator (to-string data))))
  (add-encoder
    RawJsonString
    (fn [data ^JsonGenerator jsonGenerator]
      (.writeRawValue jsonGenerator ^String (:data data)))))

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

(def encoder-escaping-null-as-replacement
  (let [ascii-escapes (CharacterEscapes/standardAsciiEscapesForJSON)
        replacement (SerializedString. "\\ufffd")]
    (aset ascii-escapes 0 CharacterEscapes/ESCAPE_CUSTOM)
    (proxy [CharacterEscapes] []
      (getEscapeSequence [ch]
        (when (zero? ch)
          replacement))
      (getEscapeCodesForAscii []
        ascii-escapes))))

(def null-replacing-json-factory
  (doto (factory/make-json-factory factory/default-factory-options)
    (.setCharacterEscapes encoder-escaping-null-as-replacement)))

(def parse-string core/parse-string)

(def parse-strict-string core/parse-string-strict)

(def parsed-seq core/parsed-seq)

(def parse-stream core/parse-stream)

(def byte-array-class (Class/forName "[B"))

(defprotocol Parser
  (-parse [obj key-fn array-coerce-fn]
    "Parses obj.  key-fn and array-coerce-fn match the corresponding
    Cheshire parse-stream arguments."))

(extend String
  Parser
  {:-parse (fn [s key-fn array-coerce-fn]
             (parse-string s key-fn array-coerce-fn))})

(extend java.io.Reader
  Parser
  {:-parse (fn [rdr key-fn array-coerce-fn]
             (parse-stream rdr key-fn array-coerce-fn))})

(defn parse
  "Parses a string, byte-array, input-stream, or reader.  key-fn and
  array-coerce-fn match the corresponding Cheshire parse-stream and
  parse-string arguments.  The top-level object will be parsed lazily
  if it's an array."
  ([x] (-parse x nil nil))
  ([x key-fn] (-parse x key-fn nil))
  ([x key-fn array-coerce-fn] (-parse x key-fn array-coerce-fn)))

(defprotocol StrictParser
  (-parse-strict [obj key-fn array-coerce-fn]
    "Eagerly parses obj.  key-fn and array-coerce-fn match the
    corresponding Cheshire parse-stream arguments."))

(extend String
  StrictParser
  {:-parse-strict (fn [s key-fn array-coerce-fn]
                    (parse-strict-string s key-fn array-coerce-fn))})

(extend byte-array-class
  StrictParser
  {:-parse-strict (fn [buf key-fn array-coerce-fn]
                    (with-open [rdr (io/reader (ByteArrayInputStream. buf)
                                               :encoding "UTF-8")]
                      (-parse-strict rdr key-fn array-coerce-fn)))})

(extend java.io.InputStream
  StrictParser
  {:-parse-strict (fn [s key-fn array-coerce-fn]
                    (with-open [rdr (io/reader s)]
                      (-parse-strict rdr key-fn array-coerce-fn)))})

(extend java.io.Reader
  StrictParser
  {:-parse-strict
   (fn [rdr key-fn array-coerce-fn]
     (parse/parse-strict
      (.createParser (or factory/*json-factory* factory/json-factory)
                     ^java.io.Reader rdr)
      key-fn nil array-coerce-fn))})

(defn parse-strict
  "Eagerly parses a string, byte-array, input-stream, or reader.
  key-fn and array-coerce-fn match the corresponding Cheshire
  parse-stream and parse-string arguments, and the top-level object
  will not be parsed lazily if it's an array."
  ([x] (-parse-strict x nil nil))
  ([x key-fn] (-parse-strict x key-fn nil))
  ([x key-fn array-coerce-fn] (-parse-strict x key-fn array-coerce-fn)))


;; Alias (apply io/writer ...) to avoid reflection
(defn writer ^Writer [f options]
  (apply io/writer f options))

(defn spit-json
  "Similar to clojure.core/spit, but writes the Clojure
   datastructure as JSON to `f`"
  [f obj & options]
  (with-open [writer (writer f options)]
    (generate-pretty-stream obj writer))
  nil)

(defn coerce-clj->json-byte-stream
  "Converts clojure data to JSON serialized bytes on a
  ByteArrayInputStream. Cheshire will only output to writers and
  stockpile will only accept input streams, so some conversion needs
  to be done in this fn"
  [payload]
  (with-open [baos (java.io.ByteArrayOutputStream.)]
    (with-open [osw (java.io.OutputStreamWriter. baos java.nio.charset.StandardCharsets/UTF_8)]
      (generate-stream payload osw))
    (-> baos
        .toByteArray
        java.io.ByteArrayInputStream.)))
