(ns puppetlabs.puppetdb.utils
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [clojure.data :as data]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.archive :as archive]
            [clojure.java.io :as io]
            [clojure.walk :as walk]
            [slingshot.slingshot :refer [try+]])
  (:import [java.net MalformedURLException URISyntaxException URL]))

(defn jdk6?
  "Returns true when the current JDK version is 1.6"
  []
  (.startsWith kitchensink/java-version "1.6"))

(defn attention-msg
  "Wraps `msg` in lots of * to draw attention to the warning"
  [msg]
  (str "********************\n"
       "*\n"
       "* " msg "\n"
       "* \n"
       "********************"))

(defn jdk-unsupported-message
  "Returns error message instructing the user to switch to JDK 1.7"
  []
  (attention-msg
   (format "JDK 1.6 is no longer supported. PuppetDB requires JDK 1.7+, currently running: %s" kitchensink/java-version)))

(defn println-err
  "Redirects output to standard error before invoking println"
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn fail-unsupported-jdk
  "If the JDK is an unsupported version, Writes error message to standard error, the log and calls fail-fn"
  [fail-fn]
  (when (jdk6?)
    (let [attn-msg (jdk-unsupported-message)]
      (println-err attn-msg)
      (log/error attn-msg)
      (fail-fn))))

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

(pls/defn-validated add-tar-entry
  :- nil
  "Writes the given `tar-item` to `tar-writer` using
   export-root-directory as the base directory for contents"
  [tar-writer
   {:keys [file-suffix contents]} :- tar-item]
  (archive/add-entry tar-writer "UTF-8"
                     (.getPath (apply io/file export-root-dir file-suffix))
                     contents))

(defn assoc-when
  "Assoc to `m` only when `k` is not already present in `m`"
  [m & kvs]
  {:pre [(even? (count kvs))]}
  (let [missing-map-entries (for [[k v] (partition 2 kvs)
                                  :when (= ::not-found (get m k ::not-found))]
                              [k v])]
    (if (seq missing-map-entries)
      (apply assoc m (apply concat missing-map-entries))
      m)))

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

(defn update-when
  "Works like update, but only if ks is found in the map(s)"
  [m ks f & args]
  (let [val (get-in m ks ::not-found)]
    (if (= val ::not-found)
      m
      (assoc-in m ks (apply f val args)))))

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
   (s/optional-key :version) (s/both
                              s/Keyword
                              (s/pred #(and (keyword? %)
                                            (re-matches #"v\d+" (name %)))
                                      'valid-version?))})

(pls/defn-validated base-url->str :- s/Str
  "Converts the `base-url' map to an ASCII URL.  May throw
   MalformedURLException or URISyntaxException."
  [{:keys [protocol host port prefix version] :as base-url} :- base-url-schema]
  (-> (URL. protocol host port
            (str prefix "/" (name (or version :v4))))
      .toURI .toASCIIString))

(defn describe-bad-base-url
  "If a problem is detected with `base-url`, returns a string
  describing the issue. For example {:host \"x:y\" ...}."
  [base-url]
  (try
    (base-url->str base-url)
    false
    (catch MalformedURLException ex (.getLocalizedMessage ex))
    (catch URISyntaxException ex (.getLocalizedMessage ex))))

(defn wrap-main
  "Returns a new main function that handles \"normal\" activities.
  For now that means that if a map containing a :utils/exit-status
  member is throw+n, then the exception's message (if any) will be
  printed to *err* and the process will exit with that status.
  Otherwise the exit status will be 0."
  [main]
  (fn [& args]
    (let [status
          (try+
           (apply main args)
           0
           (catch (and (map? %) (::exit-status %)) {:keys [::exit-status]}
             (let [msg (:message &throw-context)]
               (when-not (empty? msg)
                 (println-err (:message &throw-context))))
             exit-status))]
      (shutdown-agents)
      ;; The JVM doesn't always flush on the way out.
      (binding [*out* *err*] (flush))
      (flush)
      (System/exit status))))

(defn create-certname-pred
  "Create a function to compare the certnames in a list of
  rows with that of the first row."
  [rows]
  (let [certname (:certname (first rows))]
    (fn [row]
      (= certname (:certname row)))))

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

(defn str-schema
  "Function for converting a schema with keyword keys to
   to one with string keys. Doens't walk the map so nested
   schema won't work."
  [kwd-schema]
  (reduce-kv (fn [acc k v]
               (assoc acc (schema.core/required-key (puppetlabs.puppetdb.utils/kwd->str k)) v))
             {} kwd-schema))
