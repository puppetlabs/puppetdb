(ns com.puppetlabs.puppetdb.utils
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.tools.logging :as log]))

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
