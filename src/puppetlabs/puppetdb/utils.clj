(ns puppetlabs.puppetdb.utils
  (:refer-clojure :exclude [update-vals])
  (:require [clojure.string :as str]
            [puppetlabs.puppetdb.cli.util :refer [err-exit-status]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.i18n.core :refer [trs tru]]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [clojure.data :as data]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.archive :as archive]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.time :refer [ephemeral-now-ns]]
            [clojure.walk :as walk])
  (:import
   [clojure.lang ExceptionInfo]
   [java.net MalformedURLException URISyntaxException URL]
   [java.nio ByteBuffer CharBuffer]
   [java.nio.channels SocketChannel]
   [java.nio.charset Charset CoderResult StandardCharsets]
   (java.util.concurrent ScheduledThreadPoolExecutor TimeUnit)
   (org.apache.log4j MDC)
   (org.eclipse.jetty.io SocketChannelEndPoint)))

(defmacro with-captured-throw [& body]
  `(try [(do ~@body)] (catch Throwable ex# ex#)))

(defn println-err
  "Redirects output to standard error before invoking println"
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn print-err
  [& args]
  (binding [*out* *err*]
    (apply print args)
    (flush)))

(defmacro with-log-mdc
  "Establishes the MDC contexts given by the alternating-kvs key value
  pairs during the execution of the body, and ensures that the
  original values (if any) are always restored before returning."
  [alternating-kvs & body]
  ;; For now, assume this isn't used in performance-critical code,
  ;; i.e. within tight loops.
  (when-not (even? (count alternating-kvs))
    (throw (RuntimeException. "Odd number of MDC key value pairs")))
  (when-not (every? string? (take-nth 2 alternating-kvs))
    (throw (RuntimeException. "MDC keys are not all strings")))
  (loop [[k v & alternating-kvs] alternating-kvs
         expansion `(do ~@body)]
    (if-not k
      expansion
      (recur alternating-kvs
             ;; We know k is a string, so it's fine to repeat ~k
             `(let [v# ~v]
                (if (nil? v#)
                  ~expansion
                  (let [orig# (MDC/get ~k)]
                    (try
                      (MDC/put ~k v#)
                      ~expansion
                      (finally
                        ;; After you put a nil value MDC/getContext crashes
                        (if (nil? orig#)
                          (MDC/remove ~k)
                          (MDC/put ~k orig#)))))))))))

(defn flush-and-exit
  "Attempts to flush *out* and *err*, reporting any failures to *err*,
  if possible, and then invokes (System/exit status)."
  [status]
  (let [out-ex (try (flush) nil (catch Exception ex ex))]
    (when out-ex
      (try
        (binding [*out* *err*]
          (println "stdout flush on exit failed: " out-ex))
        (catch Exception _ nil))))
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

(def ^{:doc "Acts as update-in if ks refers to a value, otherwise returns m."
       :arglists '([m ks f & args])}
  update-when
  (let [nope (Object.)]
    (fn [m ks f & args]
      (if (identical? nope (get-in m ks nope))
        m
        (apply update-in m ks f args)))))

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
  [{:keys [protocol host port prefix version] :as _base-url} :- base-url-schema]
  (-> (URL. protocol host port
            (str prefix "/" (name (or version :v4))))
      .toURI .toASCIIString))

(pls/defn-validated base-url->str-no-path :- s/Str
  "Converts the `base-url' map to an ASCII URL minus the path element. This can
  be used to build a full URL when you have an absolute path."
  [{:keys [protocol host port] :as _base-url} :- base-url-schema]
  (-> (URL. protocol host port "")
      .toURI .toASCIIString))

(defn base-url->str-with-prefix
  [{:keys [protocol host port prefix] :as _base-url}]
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

(defn pdb-cmd-base-url
  [host port & [version protocol]]
  {:protocol (or protocol "http")
   :host host
   :port port
   :prefix "/pdb/cmd"
   :version (or version :v1)})

(defn metrics-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/metrics"
   :version (or version :v1)})

(defn cmd-url-params
  [{:keys [command version certname producer-timestamp timeout]}]
  (str
   (format "?command=%s&version=%s&certname=%s"
           (str/replace command #" " "_") version certname)
   (when producer-timestamp
     (format "&producer-timestamp=%s" producer-timestamp))
   (when timeout
     (format "&secondsToWaitForCompletion=%s" timeout))))

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
                      (str/join "|"))))

(defn optional-key? [x]
  ;; broken with AOT noidea: (instance? schema.core.OptionalKey k))
  (= schema.core.OptionalKey (class x)))

(defn str-schema
  "Function for converting a schema with keyword keys to
   to one with string keys. Doesn't walk the map so nested
   schema won't work."
  [kwd-schema]
  (reduce-kv (fn [acc k v]
               (if (optional-key? k)
                 (assoc acc (schema.core/optional-key (puppetlabs.puppetdb.utils/kwd->str (:k k))) v)
                 (assoc acc (schema.core/required-key (puppetlabs.puppetdb.utils/kwd->str k)) v)))
             {} kwd-schema))

(defn cmd-params->json-str
  [{:strs [command version certname payload]}]
  (json/generate-string
   {:command command :version (Integer/valueOf version)
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

(def byte-array-class (Class/forName "[B"))

(defmacro nil-on-failure
  "Executes `body` and if an exception is thrown, returns nil"
  [& body]
  `(try
     ~@body
     (catch Exception _#
       nil)))


;; Depending on the resolution of TK-487, we may be able to remove all
;; of this.  If so, we may also want to audit the stop methods and
;; replace conditional cleanup with unconditional cleanup for anything
;; established by init (so that we don't miss typos, etc.).

(defn throw-if-shutdown-pending
  [shutdown-reason]
  (when shutdown-reason
    (throw
     (ex-info (trs "Refusing request; PuppetDB is shutting down")
              {:kind :puppetlabs.puppetdb/shutting-down}))))

(defn call-unless-shutting-down
  [what shutting-down? shutdown-context f]
  (if-not shutting-down?
    (f)
    (do
      (log/info (trs "Skipping {0} during deferred shutdown" what))
      shutdown-context)))


(defmacro with-noisy-failure [& body]
  `(try
     ~@body
     (catch Throwable ex#
       (let [msg# (trs "Reporting unexpected error to stderr and log")]
         (binding [*out* *err*]
           (println msg#)
           (println ex#))
         (log/error ex# msg#))
       (throw ex#))))

(defmacro noisy-future [& body]
  `(future
     (with-noisy-failure
       ~@body)))

(defmacro with-fatal-error-handler
  "Calls (handler ex) instead of throwing if the body throws a fatal
  error, which is any Throwable other than Exception, AssertionError,
  or ThreadDeath."
  [handle & body]
  `(try
    ~@body
    (catch AssertionError ex#
      ;; Exempted because pdb wasn't written with any solid
      ;; expectation that :pre, :post, and assert exceptions should
      ;; always be fatal.
      (throw ex#))
    (catch ThreadDeath ex#
      ;; Exempted beccause it's part of the Thread lifecycle and also
      ;; long deprecated (never stop() a thread).
      (throw ex#))
    (catch Error ex#
      (~handle ex#))
    (catch Throwable ex#
      ;; ex should be an Exception or custom Throwable derivative
      (throw ex#))))

(defmacro with-shutdown-request-on-fatal-error
  "Calls (initiate-shutdown ex) as a side effect if the body throws a
  fatal error (see with-fatal-error-handler).  Any exceptions thrown
  by initiate-shutdown will be suppressed by (.addSuppressed ex ...)."
  [initiate-shutdown & body]
  `(with-fatal-error-handler (fn [ex#]
                               (try
                                 (~initiate-shutdown ex#)
                                 (catch Throwable ex2#
                                   (.addSuppressed ex# ex2#)))
                               (throw ex#))
     ~@body))

(defn exceptional-shutdown-requestor
  "Returns a function that when called with one Throwable argument,
  calls request-shutdown (as defined by Trapperkeeper) to request a
  shutdown with the given messages and status."
  [request-shutdown messages status]
  (fn [ex]
    (log/error (trs "Requesting shutdown: {0}" ex))
    (request-shutdown {:puppetlabs.trapperkeeper.core/exit
                       {:status status
                        :messages messages
                        ;; Current tk might just just strip this...
                        :puppetlabs.puppetdb/shutdown-cause ex}})))

(defmacro with-nonfatal-exceptions-suppressed
  "Suppresses all Throwables that are not Errors, and suppresses one
  type of Error: AssertionError."
  [& body]
  ;; See with-fatal-error-handler for additional information.
  `(try
     ~@body
     (catch AssertionError ex# nil)
     (catch ThreadDeath ex# (throw ex#))
     (catch Error ex# (throw ex#))
     (catch Throwable ex#
       ;; ex should be an Exception or custom Throwable derivative
       nil)))

(defmacro with-monitored-execution
  "Executes body while logging any exceptions and printing them to
  *err*.  Calls (initiate-shutdown ex) as a side effect for any fatal
  errors (see with-shutdown-request-on-fatal-error)."
  [initiate-shutdown & body]
  `(with-noisy-failure
     (with-shutdown-request-on-fatal-error ~initiate-shutdown
       ~@body)))

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

(defn await-ref-state
  "Waits until (pred @ref) is true and returns val, unless that takes
   longer than timeout-ms, in which case, returns timeout-val."
  ([ref pred]
   (await-ref-state ref pred nil nil))
  ([ref pred timeout-ms timeout-val]
   (let [watch-key (Object.)
         finished? (promise)
         handle-state #(when (pred %) (deliver finished? %))]
     (add-watch ref watch-key (fn [_ _ _ new] (handle-state new)))
     (try
       (handle-state @ref)
       (if timeout-ms
         (deref finished? timeout-ms timeout-val)
         (deref finished?))
       (finally
         (remove-watch ref watch-key))))))

(defn update-matching-keys
  "Returns the map resulting from an (update m k f & args) for every
  key k in m satisfying (pred k)."
  [m pred f & _]
  (reduce
   (fn [result k]
     (if (pred k)
       (update result k (fn [prev & args] (apply f k prev args)))
       result))
   m
   (keys m)))

(defn env-config-for-db-ulong [name default]
  (let [insist-positive
        #(do
           (when (neg? %)
             (throw (IllegalArgumentException. (trs "{0} is negative" name))))
           %)
        env (System/getenv name)]
    (when-not (= env "system")
      (if env
        (-> env Long/parseLong insist-positive)
        default))))


;; This closely follows the JVM ScheduledThreadPoolExceutor.  See
;; those docs for additional information.

(defn scheduler [core-threads]
  (doto (ScheduledThreadPoolExecutor. core-threads)
    (.setRemoveOnCancelPolicy true)
    (.setExecuteExistingDelayedTasksAfterShutdownPolicy false)
    (.setContinueExistingPeriodicTasksAfterShutdownPolicy false)))

(defn request-scheduler-shutdown [s interrupt-in-flight-tasks?]
  (if interrupt-in-flight-tasks?
    (.shutdownNow s)
    (.shutdown s)))

(defn await-scheduler-shutdown [s wait-time]
  (.awaitTermination s wait-time TimeUnit/MILLISECONDS))

(defn schedule [s f delay]
  (.schedule s f delay TimeUnit/MILLISECONDS))

(defn schedule-at-fixed-rate [s f initial-delay period]
  (.scheduleAtFixedRate s f initial-delay period TimeUnit/MILLISECONDS))

(defn schedule-with-fixed-delay [s f initial-delay delay]
  (.scheduleWithFixedDelay s f initial-delay delay TimeUnit/MILLISECONDS))

(defn time-limited-seq
  "Returns a new sequence of the items in coll that will call on-timeout
  (which must return something seq-ish if it doesn't throw) if the
  timeout is reached while consuming the collection.  Does nothing
  given an ##Inf timeout.  The timeout is with respect to the
  ephemeral-now-ns timeline."
  [coll deadline-ns on-timeout]
  ;; on-timeout must throw or return something seq-ish
  ;; Check before and after realizing each element
  (if (= ##Inf deadline-ns)
    coll
    (lazy-seq
     (if (> (ephemeral-now-ns) deadline-ns)
       (on-timeout)
       (when-let [s (seq coll)]
         (cons (first s)
               (if (> (ephemeral-now-ns) deadline-ns)
                 (on-timeout)
                 (time-limited-seq (rest s) deadline-ns on-timeout))))))))

(defn- last-interceptor [interceptor]
  ;; See the jetty org.eclipse.jetty.server.HttpOutput javadocs:
  ;;
  ;;   The HttpChannel is an Interceptor and is always the
  ;;   last link in any Interceptor chain.
  ;;
  ;; ...and it ends up being something that is or has a SocketChannel.
  (->> (iterate #(.getNextInterceptor %) interceptor)
       (take-while identity)
       last))

(defprotocol HasSocketChannel
  (get-socket-channel [this] "Returns the associated socket channel."))

(extend-protocol HasSocketChannel
  SocketChannel (get-socket-channel [s] s)
  SocketChannelEndPoint (get-socket-channel [s] (.getChannel s)))

(defn response->channel
  "Returns the socket channel (i.e. something that can be registered
  with a Selector) associated with a jetty response object."
  [response]
  ;; Sometimes the transport is a SocketChannel, and sometimes it's an
  ;; EndPoint.
  (-> response .getHttpOutput .getInterceptor last-interceptor
      .getEndPoint .getTransport get-socket-channel))
