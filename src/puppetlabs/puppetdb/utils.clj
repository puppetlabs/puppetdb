(ns puppetlabs.puppetdb.utils
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [schema.core :as s]
            [clojure.data :as data]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.set :as set]
            [puppetlabs.puppetdb.archive :as archive]
            [clojure.java.io :as io]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [clojure.walk :as walk]
            [slingshot.slingshot :refer [try+ throw+]]
            [com.rpl.specter :as sp])
  (:import [java.net MalformedURLException URISyntaxException URL]
           [org.postgresql.util PGobject]))

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

(pls/defn-validated base-url->str-no-path :- s/Str
  "Converts the `base-url' map to an ASCII URL minus the path element. This can
  be used to build a full URL when you have an absolute path."
  [{:keys [protocol host port] :as base-url} :- base-url-schema]
  (-> (URL. protocol host port "")
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

(defn throw+-cli-error!
  [msg]
  (throw+ {:type ::cli-error
           :message msg}))

(defn validate-cli-base-url!
  "A utility function which will validate the base-url
  and throw+ a slingshot error appropriate for `kitchensink/cli!`"
  [{:keys [base-url] :as options}]
  (when-let [why (describe-bad-base-url base-url)]
    (throw+-cli-error! (format "Invalid source (%s)" why)))
  options)

(defn try+-process-cli!
  [body]
  (try+
   (body)
   (catch map? m
     (println (:message m))
     (case (kitchensink/without-ns (:type m))
       :cli-error (System/exit 1)
       :cli-help (System/exit 0)
       (throw+ m)))))

(defn pdb-query-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/query"
   :version (or version :v4)})

(defn pdb-cmd-base-url
  [host port & [version]]
  {:protocol "http"
   :host host
   :port port
   :prefix "/pdb/cmd"
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

(defn str-schema
  "Function for converting a schema with keyword keys to
   to one with string keys. Doens't walk the map so nested
   schema won't work."
  [kwd-schema]
  (reduce-kv (fn [acc k v]
               (assoc acc (schema.core/required-key (puppetlabs.puppetdb.utils/kwd->str k)) v))
             {} kwd-schema))

(defn as-path
  "Create a url path from arguments. Does not append a slash to the beginning
   or end. Example:
   (as-path '/v4' 'facts' "
  [root & path]
  (apply str root "/" (string/join "/" path)))

(pls/defn-validated child->expansion
  "Convert child to the expanded format."
  [data :- (s/maybe (s/either PGobject s/Str))
   parent :- s/Keyword
   child :- s/Keyword
   url-prefix :- s/Str]
  (let [to-href #(as-path url-prefix (name parent) % (name child))]
    (if (string? data)
      ;; if it's a string it's just an identifier
      {:href (to-href data)}
      (-> (sutils/parse-db-json data)
          (update :href to-href)))))

(defn hsql?
  "given a db-spec style database object, determine if hsqldb is being used."
  [db-spec]
  (cond
    (:subprotocol db-spec)
    (= (:subprotocol db-spec) "hsqldb")

    (:datasource db-spec)
    (re-matches #"jdbc:hsqldb.*"
                (.getJdbcUrl (:datasource db-spec)))))

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
  [str]
  (let [result (string/replace (name str) \_ \-)]
    (if (keyword? str)
      (keyword result)
      result)))

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
