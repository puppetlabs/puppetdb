;; ## Benchmark suite
;;
;; This command-line utility will simulate catalog submission for a
;; population. It requires that a separate, running instance of
;; PuppetDB for it to submit catalogs to.
;;
;; Aspects of a population this tool currently models:
;;
;; * Number of nodes
;; * Run interval
;; * How often a host's catalog changes
;; * A starting catalog
;;
;; We attempt to approximate a number of hosts submitting catalogs at
;; the specified runinterval with the specified rate-of-churn in
;; catalog content.
;;
;; The list of nodes is modeled in the tool as a set of Clojure
;; agents, with one agent per host. Each agent has the following
;; state:
;;
;;     {:host    ;; the host's name
;;      :lastrun ;; when the host last sent a catalog
;;      :catalog ;; the last catalog sent}
;;
;; When a host needs to submit a new catalog, we determine if the new
;; catalog should be different than the previous one (based on a
;; user-specified threshold) and send the resulting catalog to
;; PuppetDB.
;;
;; ### Main loop
;;
;; The main loop is written in the form of a wall-clock
;; simulator. Each run through the main loop, we send each agent an
;; `update` message with the current wall-clock. Each agent decides
;; independently whether or not to submit a catalog during that clock
;; tick.
;;
(ns com.puppetlabs.puppetdb.cli.benchmark
  (:import (java.io File))
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.catalogs :as cat]
            [com.puppetlabs.puppetdb.catalog.utils :as catutils]
            [puppetlabs.trapperkeeper.logging :as logutils]
            [com.puppetlabs.puppetdb.command :as command]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.cheshire :as json]
            [clj-http.client :as client]
            [clj-http.util :as util]
            [fs.core :as fs]
            [com.puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clj-time.core :as time]
            [com.puppetlabs.puppetdb.command.constants :refer [command-names]]
            [slingshot.slingshot :refer [try+]]))

(def cli-description "Development-only benchmarking tool")

(defn try-load-file
  "Attempt to read and parse the JSON in `file`. If this failed, an error is
  logged, and nil is returned."
  [file]
  (try
    (json/parse-string (slurp file))
    (catch Exception e
      (log/error (format "Error parsing %s; skipping" file)))))

(defn load-sample-data
  "Load all .json files contained in `dir`."
  [dir]
  (->> (fs/glob (fs/file dir "*.json"))
       (map try-load-file)
       (remove nil?)
       (vec)))

(defn submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port catalog]
  (let [result (command/submit-command-via-http! puppetdb-host puppetdb-port
                 (command-names :replace-catalog) 1 (json/generate-string catalog))]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))

(defn submit-report
  "Send the given wire-format `report` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [puppetdb-host puppetdb-port report]
  (let [result (command/submit-command-via-http!
                 puppetdb-host puppetdb-port
                 (command-names :store-report) 1 report)]
    (when-not (= pl-http/status-ok (:status result))
      (log/error result))))

(def mutate-fns
  "Functions that randomly change a wire-catalog format"
  [catutils/add-random-resource-to-wire-catalog
   catutils/mod-resource-in-wire-catalog
   catutils/add-random-edge-to-wire-catalog
   catutils/swap-edge-targets-in-wire-catalog])

(defn rand-catalog-mutate-fn
  "Grabs one of the mutate-fns randomly and returns it"
  []
  (rand-nth mutate-fns))

(defn maybe-tweak-catalog
  "Slightly tweak the given catalog, returning a new catalog, `rand-percentage`
  percent of the time."
  [rand-percentage catalog]
  (if (< (rand 100) rand-percentage)
    ((rand-catalog-mutate-fn) catalog)
    catalog))

(defn update-report-run-fields
  "configuration-version, start-time and end-time should always change
   on subsequent report submittions, this changes those fields to avoid
   computing the same hash again (causing constraint errors in the DB)"
  [report]
  (assoc report
    "configuration-version" (kitchensink/uuid)
    "start-time" (time/now)
    "end-time" (time/now)))

(defn timed-update-host
  "Send a new _clock tick_ to a host

  On each tick, a host:

  * Determines if its time to submit a new catalog (by looking at the
    time it last sent one, and comparing that to the desired
    `runinterval`

  * If we need to submit a catalog, optionally tweak the catalog (a
    hosts catalog changes in accordance to the users preference)

  * Submit the resulting catalog"
  [{:keys [host lastrun catalog report puppetdb-host puppetdb-port run-interval rand-percentage] :as state} clock]
  (if (> (- clock lastrun) run-interval)
    (let [catalog (if catalog (maybe-tweak-catalog rand-percentage catalog))
          report (and report (update-report-run-fields report))]
      ;; Submit the catalog and reports in separate threads, so as to not
      ;; disturb the world-loop and otherwise distort the space-time continuum.
      (when catalog
        (future
          (try
            (submit-catalog puppetdb-host puppetdb-port catalog)
            (log/info (format "[%s] submitted catalog" host))
            (catch Exception e
              (log/error (format "[%s] failed to submit catalog: %s" host e))))))
      (when report
        (future
          (try
            (submit-report puppetdb-host puppetdb-port report)
            (log/info (format "[%s] submitted report" host))
            (catch Exception e
              (log/error (format "[%s] failed to submit report: %s" host e))))))
      (assoc state :lastrun clock :catalog catalog))
    state))

(defn update-host
  "Submit a `catalog` for `hosts` (when present), possibly mutating it before
   submission.  Also submit a report for the host (if present). This is
   similar to timed-update-host, but always sends the update (doesn't run/skip
   based on the clock)"
  [{:keys [host lastrun catalog report puppetdb-host puppetdb-port run-interval rand-percentage] :as state}]
  (let [catalog (and catalog (maybe-tweak-catalog rand-percentage catalog))
        report (and report (update-report-run-fields report))]
    (when catalog
      (submit-catalog puppetdb-host puppetdb-port catalog))
    (when report
      (submit-report puppetdb-host puppetdb-port report))
    (assoc state :catalog catalog)))

(defn submit-n-messages
  "Given a list of host maps, send `num-messages` to each host.  The function
   is recursive to accumulate possible catalog mutations (i.e. changing a previously
   mutated catalog as opposed to different mutations of the same catalog)."
  [hosts num-msgs]
  (printf "Sending %s messages for %s hosts, will exit upon completion" num-msgs hosts)
  (loop [mutated-hosts hosts
         msgs-to-send num-msgs]
    (when-not (zero? msgs-to-send)
      (recur (mapv update-host mutated-hosts) (dec msgs-to-send)))))

(defn world-loop
  "Sends out new _clock tick_ messages to all agents.

  This function never terminates.

  The time resolution of this loop is 10ms."
  [hosts]
  (loop [last-time (System/currentTimeMillis)]
    (let [curr-time (System/currentTimeMillis)]

      ;; Send out updated ticks to each agent
      (doseq [host hosts]
        (send host timed-update-host curr-time)
        (when-let [error (agent-error host)]
          (log/error error (format "[%s] agent failed; restarting" host))
          ;; Restart it with exactly the same state. Hopefully that's okay!
          (restart-agent host @host)))

      (Thread/sleep 10)
      (recur curr-time))))

(defn associate-catalog-with-host
  "Takes the given `catalog` and transforms it to appear related to
  `hostname`"
  [hostname catalog]
  (-> catalog
      (assoc-in ["data" "name"] hostname)
      (assoc-in ["data" "resources"] (for [resource (get-in catalog ["data" "resources"])]
                                       (update-in resource ["tags"] conj hostname)))))

(defn associate-report-with-host
  "Takes the given `report` and transforms it to appear related to
  `hostname`"
  [hostname report]
  (assoc report "certname" hostname))

(def supported-cli-options
  [["-c" "--config" "Path to config.ini or conf.d directory (required)"]
   ["-C" "--catalogs" "Path to a directory containing sample JSON catalogs (files must end with .json)"]
   ["-R" "--reports" "Path to a directory containing sample JSON reports (files must end with .json)"]
   ["-i" "--runinterval" "What runinterval (in minutes) to use during simulation"]
   ["-n" "--numhosts" "How many hosts to use during simulation"]
   ["-rp" "--rand-perc" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"]
   ["-N" "--nummsgs" "Number of commands and/or reports to send for each host"]])

(def required-cli-options
  [:config])

(defn- validate-cli!
  [args]
  (try+
    (kitchensink/cli! args supported-cli-options required-cli-options)
    (catch map? m
      (println (:message m))
      (case (:type m)
        :puppetlabs.kitchensink.core/cli-error (System/exit 1)
        :puppetlabs.kitchensink.core/cli-help (System/exit 0)))))

(defn validate-nummsgs [options action-on-error-fn]
  (when (and (contains? options :runinterval)
             (contains? options :nummsgs))
    (utils/println-err "Error: -N/--nummsgs runs immediately and is not compatable with -i/--runinterval")
    (action-on-error-fn)))

(defn -main
  [& args]
  (let [[options _]     (kitchensink/cli! args supported-cli-options required-cli-options)
        config          (-> (:config options)
                            (kitchensink/inis-to-map)
                            (get-in [:global :logging-config])
                            (logutils/configure-logging!))

        _ (validate-nummsgs options #(System/exit 1))

        catalogs        (if (:catalogs options) (load-sample-data (:catalogs options)))
        reports         (if (:reports options) (load-sample-data (:reports options)))

        nhosts          (:numhosts options)
        hostnames       (set (map #(str "host-" %) (range (Integer/parseInt nhosts))))
        hostname        (get-in config [:jetty :host] "localhost")
        port            (get-in config [:jetty :port] 8080)
        rand-percentage (Integer/parseInt (:rand-perc options))

        ;; Create an agent for each host
        hosts (mapv (fn [host]
                      {:host host
                       :puppetdb-host hostname
                       :puppetdb-port port
                       :rand-percentage rand-percentage
                       :catalog (when catalogs
                                  (associate-catalog-with-host host (rand-nth catalogs)))
                       :report (when reports
                                 (associate-report-with-host host (rand-nth reports)))})
                    hostnames)]

    (when-not catalogs
      (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports
      (log/info "No reports specified; skipping report submission"))
    (if-let [num-cmds (:nummsgs options)]
      (submit-n-messages hosts (Long/valueOf num-cmds))
      (world-loop (mapv (fn [host-map]
                          (let [run-interval (* 60 1000 (Integer/parseInt (:runinterval options)))]
                            (agent (assoc host-map
                                     :run-interval run-interval
                                     :lastrun (- (System/currentTimeMillis) (rand-int run-interval))))))
                        hosts)))))
