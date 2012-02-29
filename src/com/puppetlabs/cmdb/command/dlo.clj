(ns com.puppetlabs.cmdb.command.dlo
  (:require [clojure.string :as string]
            [com.puppetlabs.utils :as pl-utils]
            [cheshire.core :as json])
  (:use [clojure.java.io :only [file make-parents]]))

(defn summarize-attempt
  "Convert an 'attempt' annotation for a message into a string summary,
  including timestamp, error information, and stacktrace."
  [index {:keys [timestamp error trace] :as attempt}]
  (let [trace-str (string/join "\n" trace)
        index (if (nil? index) index (inc index))]
    (format "Attempt %d @ %s\n\n%s\n%s\n" index timestamp error trace-str)))

(defn summarize-exception
  "Convert a Throwable into a string summary similar to the output of
  summarize-attempt."
  [e]
  (let [attempt {:timestamp (pl-utils/timestamp)
                 :error     (str e)
                 :trace     (.getStackTrace e)}]
    (summarize-attempt nil attempt)))

(defn produce-failure-metadata
  "Given a (possibly empty) sequence of message attempts and an exception,
  return a header string of the errors."
  [attempts exception]
  (let [attempt-summaries (map-indexed summarize-attempt attempts)
        exception-summary (if exception (summarize-exception exception))]
    (string/join "\n" (concat attempt-summaries [exception-summary]))))

(defn store-failed-message
  "Stores a failed message for later inspection. This will be stored under
  `dir`, in a path shaped like `dir`/<command>/<timestamp>-<checksum>. If the
  message was not parseable, `command` will be parse-error."
  [msg e dir]
  (let [command  (get msg :command "parse-error")
        attempts (get-in msg [:annotations :attempts])
        metadata (produce-failure-metadata attempts e)
        msg      (if (string? msg) msg (json/generate-string msg))
        contents (string/join "\n\n" [msg metadata])
        checksum (pl-utils/utf8-string->sha1 contents)
        subdir   (string/replace command " " "-")
        basename (format "%s-%s" (pl-utils/timestamp) checksum)
        filename (file dir subdir basename)]
    (make-parents filename)
    (spit filename contents)))

