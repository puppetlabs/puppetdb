(ns puppetlabs.puppetdb.utils
  (:require [clojure.string :as str]
            [puppetlabs.puppetdb.cli.util :refer [err-exit-status]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.i18n.core :refer [trs tru]]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [schema.core :as s]
            [clojure.data :as data]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.set :as set]
            [puppetlabs.puppetdb.archive :as archive]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.walk :as walk]
            [com.rpl.specter :as sp])
  (:import
   [clojure.lang ExceptionInfo]
   [java.net MalformedURLException URISyntaxException URL]
   [java.nio ByteBuffer CharBuffer]
   [java.nio.charset Charset CharsetEncoder CoderResult StandardCharsets]
   [org.postgresql.util PGobject]))

(defmacro with-captured-throw [& body]
  `(try [(do ~@body)] (catch Throwable ex# ex#)))

(defn println-err
  "Redirects output to standard error before invoking println"
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn re-quote
  "Quotes s so that all of its characters will be matched literally."
  [s]
  ;; Wrap all segments not containing a \E in \Q...\E, and replace \E
  ;; with \\E.
  (apply str
         "\\Q"
         (concat (->> (str/split s #"\\E" -1)
                      (interpose "\\E\\\\E\\Q"))
                 ["\\E"])))

(defn flush-and-exit [status]
  "Attempts to flush *out* and *err*, reporting any failures to *err*,
  if possible, and then invokes (System/exit status)."
  (let [out-ex (try (flush) nil (catch Exception ex ex))]
    (when out-ex
      (try
        (binding [*out* *err*]
          (println "stdout flush on exit failed: " out-ex)
          (catch Exception _ nil)))))
  (try
    (binding [*out* *err*] (flush))
    (catch Exception _ nil))
  (System/exit status))

(pls/defn-validated diff-fn
  "Run clojure.data/diff on `left` and `right`, calling `left-only-fn`, `right-only-fn` and `same-fn` with
   the results of the call. Those functions should always receive a non-nil argument (though possibly empty)."
  [left :- {s/Any s/Any}
   right :- {s/Any s/Any}
   left-only-fn :- pls/Function
   right-only-fn :- pls/Function
   same-fn :- pls/Function]
  (let [[left-only right-only same] (data/diff (kitchensink/keyset left) (kitchensink/keyset right))]
    (left-only-fn (or left-only #{}))
    (right-only-fn (or right-only #{}))
    (same-fn (or same #{}))))

(def tar-item {(s/optional-key :msg) String
               :file-suffix [String]
               :contents String})

(def export-root-dir "puppetdb-bak")

(pls/defn-validated add-tar-entry :- nil
  "Writes the given `tar-item` to `tar-writer` using
   export-root-directory as the base directory for contents"
  [tar-writer
   {:keys [file-suffix contents]} :- tar-item]
  (archive/add-entry tar-writer "UTF-8"
                     (.getPath (apply io/file export-root-dir file-suffix))
                     contents))

(defn read-json-content
  "Utility function for our cli tools.
  For reading json content from a tar-reader."
  ([reader] (read-json-content reader false))
  ([reader keywordize-keys?]
   (-> reader archive/read-entry-content (json/parse-string keywordize-keys?))))

(defmacro assoc-when
  "Assocs the provided values with the corresponding keys if and only
  if the key is not already present in map."
  [map key val & kvs]
  {:pre [(even? (count kvs))]}
  (let [deferred-kvs (vec (for [[k v] (cons [key val] (partition 2 kvs))]
                            [k `(fn [] ~v)]))]
    `(let [updates# (for [[k# v#] ~deferred-kvs
                          :when (= ::not-found (get ~map k# ::not-found))]
                      [k# (v#)])]
       (merge ~map (into {} updates#)))))

(pls/defn-validated kwd->str
  "Convert a keyword to a string. This is different from `name` in
  that it will preserve the entire keyword, i.e. :foo/bar ->
  \"foo/bar\", where name would be just \"bar\""
  [kwd :- s/Keyword]
  (-> kwd
      str
      (subs 1)))

(defn stringify-keys
  "Recursively transforms all map keys from keywords to strings. This improves
  on clojure.walk/stringify-keys by supporting the conversion of hyphenated
  keywords to strings instead of trying to resolve them in a namespace first."
  [m]
  (let [f (fn [[k v]] (if (keyword? k)
                        [(kwd->str k) v]
                        [k v]))]
    ;; only apply to maps
    (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(pls/defn-validated digit? :- s/Bool
  "Return true if the character is a digit"
  [c :- Character]
  (and (>= 0 (compare \0 c))
       (>= 0 (compare c \9))))

(defn update-vals
  "This function is like update-in, except the vector argument contains top-level
  keys rather than nested.  Applies function f to values corresponding to keys
  ks in map m."
  [m ks f]
  (reduce #(update-in %1 [%2] f) m ks))

(defn update-cond
  "Works like update, but only if pred is satisfied"
  [m pred ks f & args]
  (if pred
    (apply update-in m ks f args)
    m))

(defn update-when
  "Works like update, but only if ks is found in the map(s)"
  [m ks f & args]
  (let [val (get-in m ks ::not-found)]
   (apply update-cond m (not= val ::not-found) ks f args)))

(defn vector-maybe
  "Vectorize an argument if it's not already vector"
  [v]
  (if (vector? v) v (vector v)))

(defn collapse-seq
  "Lazily consumes and collapses the seq `rows`. Uses `split-pred` to chunk the seq,
  passes in each chunk to `collapse-fn`. Each result of `collapse-fn` is an item in
  the return lazy-seq."
  [split-pred collapse-fn rows]
  (when (seq rows)
    (let [[certname-facts more-rows] (split-with (split-pred rows) rows)]
      (cons (collapse-fn certname-facts)
            (lazy-seq (collapse-seq split-pred collapse-fn more-rows))))))

(def base-url-schema
  {:protocol s/Str
   :host s/Str
   :port s/Int
   (s/optional-key :prefix) s/Str
   (s/optional-key :version) (s/constrained s/Keyword #(re-matches #"v\d+" (name %)))})

(pls/defn-validated base-url->str :- s/Str
  "Converts the `base-url' map to an ASCII URL.  May throw
   MalformedURLException or URISyntaxException."
  [{:keys [protocol host port prefix version] :as base-url} :- base-url-schema]
  (-> (URL. protocol host port
            (str prefix "/" (name (or version :v4))))
      .toURI .toASCIIString))

(pls/defn-validated base-url->str-no-path :- s/Str
  "Converts the `base-url' map to an ASCII URL minus the path element. This can
  be used to build a full URL when you have an absolute path."
  [{:keys [protocol host port] :as base-url} :- base-url-schema]
  (-> (URL. protocol host port "")
      .toURI .toASCIIString))

(defn base-url->str-with-prefix
  [{:keys [protocol host port prefix] :as base-url}]
  (-> (java.net.URL. protocol host port prefix)
      .toURI
      .toASCIIString))

(defn describe-bad-base-url
  "If a problem is detected with `base-url`, returns a string
  describing the issue. For example {:host \"x:y\" ...}."
  [base-url]
  (try
    (base-url->str base-url)
    false
    (catch MalformedURLException ex (.getLocalizedMessage ex))
    (catch URISyntaxException ex (.getLocalizedMessage ex))))

(defn throw-sink-cli-error
  [msg]
  (throw (ex-info msg (kitchensink/error-map ::kitchensink/cli-error msg))))

(defn validate-cli-base-url!
  "Validates the base-url and throws an exception appropriate for
  kitchensink/cli! on error."
  [{:keys [base-url] :as options}]
  (when-let [why (describe-bad-base-url base-url)]
    (throw-sink-cli-error (format "Invalid source (%s)" why)))
  options)

(defn try-process-cli
  [f]
  (try
    (f)
    (catch ExceptionInfo ex
      (let [{:keys [kind msg]} (ex-data ex)]
        (case kind
          ::kitchensink/cli-error
          (do
            (binding [*out* *err*] (println msg))
            (flush-and-exit err-exit-status))
          ::kitchensink/cli-help
          (do
            (println msg)
            (flush-and-exit 0))
          (throw ex))))))

(defn pdb-query-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/query"
   :version (or version :v4)})

(defn pdb-admin-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/admin"
   :version (or version :v1)})

(defn pdb-cmd-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/cmd"
   :version (or version :v1)})

(defn pdb-meta-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/meta"
   :version (or version :v1)})

(defn metrics-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/metrics"
   :version (or version :v1)})

(defn assoc-if-exists
  "Assoc only if the key is already present"
  [m & ks]
  (let [new-kvs (->> ks
                     (partition 2)
                     (filter #(get m (first %)))
                     flatten)]
    (if-not (empty? new-kvs)
      (apply assoc m new-kvs)
      m)))

(defn maybe-strip-escaped-quotes
  [s]
  (if (and (> (count s) 1)
           (string/starts-with? s "\"")
           (string/ends-with? s "\""))
    (subs s 1 (dec (count s)))
    s))

(defn quoted
  [s]
  (str "'" s "'"))

(defn comma-separated-keywords
  [words]
  (let [quoted-words (map quoted words)]
    (if (> (count quoted-words) 2)
      (str (string/join ", " (butlast quoted-words)) ", " "and " (last quoted-words))
      (string/join " and " quoted-words))))

(defn parse-matchfields
  [s]
  (string/replace s #"match\((\".*\")\)" "$1"))

(defn parse-indexing
  [s]
  (string/replace s #"\[(\d+)\]" ".$1"))

(defn split-indexing
  [path]
  (flatten
    (for [s path]
      (if (re-find #"\[\d+\]$" s)
        (-> s
            (string/split #"(?=\[\d+\]$)")
            (update 1 #(Integer/parseInt (subs % 1 (dec (count %))))))
        s))))

(defn regex-quote
  [s]
  (when (and (string? s) (re-find #"\\E" s))
    (throw (IllegalArgumentException.
            (tru "cannot regex-quote strings containing ''\\E''"))))
  (format "\\Q%s\\E" (str s)))

(defn match-any-of
  "Given a collection of strings and characters, construct a regex string
  suitable for passing to re-pattern that consists of a capturing group which
  matches any member of the collection."
  [strings]
  (format "(%s)" (->> strings
                      (map regex-quote)
                      (string/join "|"))))

(defn optional-key? [x]
  ;; broken with AOT noidea: (instance? schema.core.OptionalKey k))
  (= schema.core.OptionalKey (class x)))

(defn str-schema
  "Function for converting a schema with keyword keys to
   to one with string keys. Doens't walk the map so nested
   schema won't work."
  [kwd-schema]
  (reduce-kv (fn [acc k v]
               (if (optional-key? k)
                 (assoc acc (schema.core/optional-key (puppetlabs.puppetdb.utils/kwd->str (:k k))) v)
                 (assoc acc (schema.core/required-key (puppetlabs.puppetdb.utils/kwd->str k)) v)))
             {} kwd-schema))

(defn dashes->underscores
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
  dash/hyphen character with an underscore, and returns the same type (string
  or keyword) that was passed in.  This is useful for translating data structures
  from their wire format to the format that is needed for JDBC."
  [str]
  (let [result (string/replace (name str) \- \_)]
    (if (keyword? str)
      (keyword result)
      result)))

(defn underscores->dashes
  "Accepts a string or a keyword as an argument, replaces all occurrences of the
   underscore character with a dash, and returns the same type (string
   or keyword) that was passed in.  This is useful for translating data structures
   from their JDBC-compatible representation to their wire format representation."
  [s]
  (let [opt-key? (optional-key? s)
        result (if opt-key?
                 (string/replace (name (:k s)) \_ \-)
                 (string/replace (name s) \_ \-))]
    (cond
      opt-key? (if (keyword? (:k s))
                 (s/optional-key (keyword result))
                 (s/optional-key result))
      (keyword? s) (keyword result)
      :else result)))

(defn dash->underscore-keys
  "Converts all top-level keys (including nested maps) in `m` to use dashes
  instead of underscores as word separatators"
  [m]
  (sp/transform [sp/ALL]
                #(update % 0 dashes->underscores)
                m))

(defn underscore->dash-keys
  "Converts all top-level keys (including nested maps) in `m` to use underscores
  instead of underscores as word separatators"
  [m]
  (sp/transform [sp/ALL]
                #(update % 0 underscores->dashes)
                m))

(defn cmd-params->json-str
  [{:strs [command version certname payload]}]
  (json/generate-string
    {:command command :version (Integer. version)
     :certname certname :payload (json/->RawJsonString payload)}))

(def ^Charset utf8 StandardCharsets/UTF_8)

(defn utf8-bytes [s] (.getBytes ^String s utf8))

(defn utf8-length
  "Return the length in bytes of the given string when encoded in UTF-8"
  [s]
  (count (.getBytes ^String s utf8)))

(defn utf8-truncate
  "Truncate the given string such that its UTF-8 representation is at most
  `max-bytes` long. Note that the returned string may be empty."
  [s max-bytes]
  (let [utf8-bytebuff (ByteBuffer/allocate max-bytes)
        chars-in (CharBuffer/wrap (.toCharArray ^String s))
        encoder (.newEncoder utf8)
        encode-result (.encode encoder chars-in utf8-bytebuff true)
        flush-result (.flush encoder utf8-bytebuff)]
    (doseq [^CoderResult result [encode-result flush-result]]
      (when (.isError result)
        (.throwException result)))
    (String. (.array utf8-bytebuff) 0 (.position utf8-bytebuff) utf8)))

(defmacro with-timeout [timeout-ms default & body]
  `(let [f# (future (do ~@body))
         result# (deref f# ~timeout-ms ~default)]
     result#))

(def byte-array-class (Class/forName "[B"))

(defmacro nil-on-failure
  "Executes `body` and if an exception is thrown, returns nil"
  [& body]
  `(try
     ~@body
     (catch Exception _#
       nil)))

(defmacro noisy-future [& body]
  `(future
     (try
       ~@body
       (catch Throwable ex#
         (let [msg# (trs "Reporting unexpected error to stderr and log")]
           (binding [*out* *err*]
             (println msg#)
             (println ex#))
           (log/error ex# msg#))
         (throw ex#)))))

;; For now, if you change these extensions, make sure they satisfy
;; validate-compression-extension-syntax in the queue.

(def content-encodings->file-extensions
  {"gzip" "gz"
   "identity" ""})

(defn content-encoding->file-extension
  [encoding]
  (get content-encodings->file-extensions encoding ""))

(def compression-file-extension-schema
  (->> content-encodings->file-extensions
       vals
       (cons "")
       (apply s/enum)))

(def supported-content-encodings
  (vec (keys content-encodings->file-extensions)))

(defn strip-nil-values
  "remove all nil-valued keys from a map"
  [m]
  (into {} (filter val m)))

(defn wait-for-ref-state [ref ms pred]
  (let [watch-key wait-for-ref-state
        finished? (promise)
        handle-state #(when (pred %) (deliver finished? true))]
    (add-watch ref watch-key (fn [_ _ _ new] (handle-state new)))
    (try
      (handle-state @ref)
      (deref finished? ms false)
      (finally
        (remove-watch ref watch-key)))))
