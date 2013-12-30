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

(defn attention-warning-msg
  "Wraps `msg` in lots of * to draw attention to the warning"
  [msg]
  (str "********************\n"
       "*\n"
       "* " msg "\n"
       "* \n"
       "********************"))

(defn jdk-deprecation-message
  "Returns warning message instructing the user to switch to JDK 1.7"
  []
  (attention-warning-msg
   (format "Warning - Support for JDK 1.6 has been deprecated. PuppetDB requires JDK 1.7+, currently running: %s" kitchensink/java-version)))

(defn println-err
  "Redirects output to standard error before invoking println"
  [& args]
  (binding [*out* *err*]
    (apply println args)))

(defn log-deprecated-jdk
  "Checks the current JDK version, logs a deprecation message if it's 1.6"
  []
  (when (jdk6?)
    (let [attn-msg (jdk-deprecation-message)]
      (log/error attn-msg))))

(defn alert-deprecated-jdk
  "Similar to log-deprecated-jdk, but also prints the deprecation message to standard error"
  []
  (when (jdk6?)
    (let [attn-msg (jdk-deprecation-message)]
      (println-err attn-msg)
      (log/error attn-msg))))

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

