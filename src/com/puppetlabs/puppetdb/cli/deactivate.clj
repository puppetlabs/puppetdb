;; ## Node deactivation
;;
;; This utility is used to issue a `deactivate node` request to a running
;; Grayskull instance, which will record the node as inactive until it receives
;; another command. Inactive nodes can be filtered from fact and resource
;; queries.
;;
;; The only arguments to the command are a config file, used to find the
;; Grayskull server, and a list of nodes to attempt to deactivate. Because this
;; is an asynchronous operation, no feedback is available about whether the
;; command was actually fulfilled, and it may not be effective immediately.
;;
;; If every command submission succeeds, the application will exit 0.
;; Otherwise, it will exit with the number of failed command submissions.
;;
(ns com.puppetlabs.cmdb.cli.deactivate
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.cmdb.command :as command])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map)]))

(def cli-description "Mark nodes as inactive/decommissioned")

(defn deactivate
  "Submits a 'deactivate node' request for `node` to the Grayskull instance
  specified by `host` and `port`. Returns a true value if submission succeeded,
  and a false value otherwise."
  [node host port]
  (let [result (command/submit-command host port node "deactivate node" 1)]
    (if (= 200 (:status result))
      true
      (log/error result))))

(defn -main
  [& args]
  (let [[options nodes] (cli! args
                              ["-c" "--config" "Path to config.ini"])
        config      (ini-to-map (:config options))
        host        (get-in config [:jetty :host] "localhost")
        port        (get-in config [:jetty :port] 8080)
        failures    (->> nodes
                      (map (fn [node]
                             (log/info (str "Submitting deactivation command for " node))
                             (deactivate node host port)))
                      (filter (complement identity))
                      (count))]
    (System/exit failures)))
