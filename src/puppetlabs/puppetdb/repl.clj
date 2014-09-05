(ns puppetlabs.puppetdb.repl
  (:import [vimclojure.nailgun NGServer])
  (:require [clojure.string :as string]
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.tools.nrepl.transport :as nrepl-transport]))

(defmulti start-repl
  "Starts and instance of the specified `kind` of REPL, listening on `host` and
  `port`."
  (fn [kind host port]
    (string/lower-case kind)))

(defmethod start-repl "telnet"
  [kind host port]
  (nrepl/start-server :bind host :port port :transport-fn nrepl-transport/tty :greeting-fn nrepl-transport/tty-greeting))

(defmethod start-repl "nrepl"
  [kind host port]
  (nrepl/start-server :bind host :port port))

(defmethod start-repl "vimclojure"
  [kind host port]
  (vimclojure.nailgun.NGServer/main (into-array String [(str host ":" port)])))
