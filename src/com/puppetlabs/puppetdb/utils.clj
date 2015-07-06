(ns com.puppetlabs.puppetdb.utils
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [clojure.data :as data]
            [com.puppetlabs.puppetdb.schema :as pls]
            [com.puppetlabs.archive :as archive]
            [clojure.java.io :as io]
            [schema.core :as s]
            [clojure.walk :as walk]))

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
  {:pre [(map? left)
         (map? right)
         (fn? left-only-fn)
         (fn? right-only-fn)
         (fn? same-fn)]}
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

(defn stringify-keys
  "Recursively transforms all map keys from keywords to strings. This improves
   on clojure.walk/stringify-keys by supporting the conversion of hyphenated
   keywords to strings instead of trying to resolve them in a namespace first."
  [m]
  (let [f (fn [[k v]] (if (keyword? k)
                        [(subs (str k) 1) v] [k v]))]
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
