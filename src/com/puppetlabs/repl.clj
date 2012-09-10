(ns com.puppetlabs.repl
  (:import [vimclojure.nailgun NGServer])
  (:require [clojure.string :as string]
            [clojure.tools.nrepl.server :as nrepl]
            [swank.swank :as swank])
  (:use [clojure.tools.nrepl.transport :only (tty tty-greeting)]))

(defmulti start-repl
  "Starts and instance of the specified `kind` of REPL, listening on `host` and
  `port`."
  (fn [kind host port]
    (string/lower-case kind)))

(defmethod start-repl "nrepl"
  [kind host port]
  (nrepl/start-server :bind host :port port :transport-fn tty :greeting-fn tty-greeting))

(defmethod start-repl "swank"
  [kind host port]
  (swank/start-server :host host :port port))

(defmethod start-repl "vimclojure"
  [kind host port]
  (vimclojure.nailgun.NGServer/main (into-array String [(str host ":" port)])))
