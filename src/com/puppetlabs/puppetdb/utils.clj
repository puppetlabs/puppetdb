(ns com.puppetlabs.puppetdb.utils
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [clojure.data :as data]
            [com.puppetlabs.puppetdb.schema :as pls]))

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

(s/defn diff-fn
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

