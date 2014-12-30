(ns puppetlabs.puppetdb.cli.benchmark
  "Benchmark suite

   This command-line utility will simulate catalog submission for a
   population. It requires that a separate, running instance of
   PuppetDB for it to submit catalogs to.

   Aspects of a population this tool currently models:

   * Number of nodes
   * Run interval
   * How often a host's catalog changes
   * A starting catalog

   We attempt to approximate a number of hosts submitting catalogs at
   the specified runinterval with the specified rate-of-churn in
   catalog content.

   The list of nodes is modeled in the tool as a set of Clojure
   agents, with one agent per host. Each agent has the following
   state:

       {:host    ;; the host's name
        :lastrun ;; when the host last sent a catalog
        :catalog ;; the last catalog sent}

   When a host needs to submit a new catalog, we determine if the new
   catalog should be different than the previous one (based on a
   user-specified threshold) and send the resulting catalog to
   PuppetDB.

   ### Main loop

   The main loop is written in the form of a wall-clock
   simulator. Each run through the main loop, we send each agent an
   `update` message with the current wall-clock. Each agent decides
   independently whether or not to submit a catalog during that clock
   tick."
  (:import (java.io File))
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.catalog.utils :as catutils]
            [puppetlabs.trapperkeeper.logging :as logutils]
            [puppetlabs.puppetdb.cheshire :as json]
            [fs.core :as fs]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as ks]
            [clj-time.core :as time]
            [puppetlabs.puppetdb.client :as client]
            [puppetlabs.puppetdb.random :refer [random-string random-bool]]
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

(defn random-fact-value
  "Given a type, generate a random fact value"
  [kind]
  (case kind
    :int (rand-int 300)
    :float (rand)
    :bool (random-bool)
    :string (random-string 4)
    :vector (into [] (take (rand-int 10)
                           (repeatedly #(random-fact-value
                                         (rand-nth [:string :int :float :bool])))))))

(defn random-structured-fact
  "Create a 'random' structured fact.
  Parameters are fact depth and number of child facts.  Depth 0 implies one child."
  ([]
     (random-structured-fact (rand-nth [0 1 2 3]) (rand-nth [1 2 3 4])))
  ([depth children]
     (let [kind (rand-nth [:int :float :bool :string :vector])]
       (if (zero? depth)
         {(random-string 10) (random-fact-value kind)}
         {(random-string 10) (zipmap (take children (repeatedly #(random-string 10)))
                                     (take children (repeatedly
                                                     #(random-structured-fact
                                                       (rand-nth (range depth))
                                                       (rand-nth (range children))))))}))))

(defn populate-database-with-facts
  "This will populate a database with semi-random structured facts.

  Aside from database host, port, and fact command version, arguments are

  *nodes : number of nodes to be submitted
  *dup-rate : target duplication rated defined as 1 - (#distinct fact values)/(#facts)
  *facts-per-node : number of facts per node

  This function is only suitable for *rough* load testing, as its output has
  not been compared to real user data."
  [host port facts-version nodes dup-rate facts-per-node]
  (let [name-pool (take (Math/round (* dup-rate facts-per-node)) (repeatedly #(random-string 10)))
        facts-pool (take (* dup-rate facts-per-node) (repeatedly #(random-structured-fact)))]
    (doseq [[certname env] (take nodes (repeatedly #(vector (random-string 10) (random-string 10))))]
      (let [fact-payload {:environment env
                          :name certname
                          :values (apply merge (take facts-per-node
                                                     (repeatedly #(if (< (rand) dup-rate)
                                                                    (rand-nth facts-pool)
                                                                    (random-structured-fact)))))}]
        (client/submit-facts host port facts-version (json/generate-string fact-payload))))))

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
    "configuration-version" (ks/uuid)
    "start-time" (time/now)
    "end-time" (time/now)))

(defn randomize-map-leaf
  "Randomizes a fact leaf based on a percentage provided with `rp`."
  [rp leaf]
  (if (< (rand 100) rp)
    (cond
     (string? leaf)  (random-string (inc (rand-int 100)))
     (integer? leaf) (rand-int 100000)
     (float? leaf)   (* (rand) (rand-int 100000))
     (ks/boolean? leaf) (random-bool))
    leaf))

(defn randomize-map-leaves
  "Runs through a map and randomizes leafs based on a percentage provided with
   `rp`."
  [rp value]
  (cond
   (map? value)
   (into {}
         (for [[k v] value]
           [k (randomize-map-leaves rp v)]))

   (coll? value)
   (for [v value]
     (randomize-map-leaves rp v))

   :else
   (randomize-map-leaf rp value)))

(defn update-factset
  "Updates the producer-timestamp to be current, and randomly updates the leaves
   of the factset based on a percentage provided in `rand-percentage`."
  [rand-percentage factset]
  (-> factset
      (assoc "producer-timestamp" (time/now))
      (update-in ["values"] (partial randomize-map-leaves rand-percentage))))

(defn timed-update-host
  "Send a new _clock tick_ to a host

  On each tick, a host:

  * Determines if its time to submit a new catalog (by looking at the
    time it last sent one, and comparing that to the desired
    `runinterval`

  * If we need to submit a catalog, optionally tweak the catalog (a
    hosts catalog changes in accordance to the users preference)

  * Submit the resulting catalog"
  [{:keys [host lastrun catalog report factset puppetdb-host puppetdb-port run-interval rand-percentage] :as state} clock]
  (if (> (- clock lastrun) run-interval)
    (let [base-url {:protocol "http" :host puppetdb-host :port puppetdb-port}
          catalog (when catalog (maybe-tweak-catalog rand-percentage catalog))
          report (and report (update-report-run-fields report))
          factset (and factset (update-factset rand-percentage factset))]
      ;; Submit the catalog and reports in separate threads, so as to not
      ;; disturb the world-loop and otherwise distort the space-time continuum.
      (when catalog
        (future
          (try
            (client/submit-catalog base-url 5 (json/generate-string catalog))
            (log/info (format "[%s] submitted catalog" host))
            (catch Exception e
              (log/error (format "[%s] failed to submit catalog: %s" host e))))))
      (when report
        (future
          (try
            (client/submit-report base-url 3 (json/generate-string report))
            (log/info (format "[%s] submitted report" host))
            (catch Exception e
              (log/error (format "[%s] failed to submit report: %s" host e))))))
      (when factset
        (future
          (try
            (client/submit-facts base-url 3 (json/generate-string factset))
            (log/info (format "[%s] submitted factset" host))
            (catch Exception e
              (log/error (format "[%s] failed to submit factset: %s" host e))))))
      (assoc state
        :lastrun clock
        :catalog catalog))
    state))

(defn update-host
  "Submit a `catalog` for `hosts` (when present), possibly mutating it before
   submission.  Also submit a report for the host (if present). This is
   similar to timed-update-host, but always sends the update (doesn't run/skip
   based on the clock)"
  [{:keys [host lastrun catalog report factset puppetdb-host puppetdb-port run-interval rand-percentage] :as state}]
  (let [base-url {:protocol "http" :host puppetdb-host :port puppetdb-port}
        catalog (and catalog (maybe-tweak-catalog rand-percentage catalog))
        report (and report (update-report-run-fields report))
        factset (and factset (update-factset rand-percentage factset))]
    (when catalog
      (client/submit-catalog base-url 5 (json/generate-string catalog)))
    (when report
      (client/submit-report base-url 3 (json/generate-string report)))
    (when factset
      (client/submit-facts base-url 3 (json/generate-string factset)))
    (assoc state :catalog catalog)))

(defn submit-n-messages
  "Given a list of host maps, send `num-messages` to each host.  The function
   is recursive to accumulate possible catalog mutations (i.e. changing a previously
   mutated catalog as opposed to different mutations of the same catalog)."
  [hosts num-msgs]
  (log/info (format "Sending %s messages for %s hosts, will exit upon completion"
                    num-msgs (count hosts)))
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
  (assoc catalog
    "name" hostname
    "resources" (map #(update-in % ["tags"] conj hostname) (get catalog "resources"))))

(defn associate-report-with-host
  "Takes the given `report` and transforms it to appear related to
  `hostname`"
  [hostname report]
  (assoc report "certname" hostname))

(defn associate-factset-with-host
  "Takes the given `factset` and transforms it to appear related to
   `hostname`"
  [hostname factset]
  (assoc factset "name" hostname))

(def supported-cli-options
  [["-c" "--config CONFIG" "Path to config.ini or conf.d directory (required)"]
   ["-F" "--facts FACTS" "Path to a directory containing sample JSON facts (files must end with .json)"]
   ["-C" "--catalogs CATALOGS" "Path to a directory containing sample JSON catalogs (files must end with .json)"]
   ["-R" "--reports REPORTS" "Path to a directory containing sample JSON reports (files must end with .json)"]
   ["-i" "--runinterval RUNINTERVAL" "What runinterval (in minutes) to use during simulation"]
   ["-n" "--numhosts NUMHOSTS" "How many hosts to use during simulation"]
   ["-r" "--rand-perc RANDPERC" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"]
   ["-N" "--nummsgs NUMMSGS" "Number of commands and/or reports to send for each host"]])

(def required-cli-options
  [:config])

(defn- validate-cli!
  [args]
  (try+
   (ks/cli! args supported-cli-options required-cli-options)
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
  (let [[options _]     (validate-cli! args)
        config          (-> (:config options)
                            (ks/inis-to-map)
                            (get-in [:global :logging-config])
                            (logutils/configure-logging!))

        _ (validate-nummsgs options #(System/exit 1))

        catalogs        (if (:catalogs options) (load-sample-data (:catalogs options)))
        reports         (if (:reports options) (load-sample-data (:reports options)))
        facts           (if (:facts options) (load-sample-data (:facts options)))

        nhosts          (:numhosts options)
        hostnames       (set (map #(str "host-" %) (range (Integer/parseInt nhosts))))
        hostname        (get-in config [:jetty :host] "localhost")
        port            (get-in config [:jetty :port] 8080)
        rand-percentage (Integer/parseInt (or (:rand-perc options) "0"))

        ;; Create an agent for each host
        hosts (mapv (fn [host]
                      {:host host
                       :puppetdb-host hostname
                       :puppetdb-port port
                       :rand-percentage rand-percentage
                       :catalog (when catalogs
                                  (associate-catalog-with-host host (rand-nth catalogs)))
                       :report (when reports
                                 (associate-report-with-host host (rand-nth reports)))
                       :factset (when facts
                                   (associate-factset-with-host host (rand-nth facts)))})
                    hostnames)]

    (when-not catalogs
      (log/info "No catalogs specified; skipping catalog submission"))
    (when-not reports
      (log/info "No reports specified; skipping report submission"))
    (when-not facts
      (log/info "No facts specified; skipping fact submission"))
    (if-let [num-cmds (:nummsgs options)]
      (submit-n-messages hosts (Long/valueOf num-cmds))
      (world-loop (mapv (fn [host-map]
                          (let [run-interval (* 60 1000 (Integer/parseInt (:runinterval options)))]
                            (agent (assoc host-map
                                     :run-interval run-interval
                                     :lastrun (- (System/currentTimeMillis) (rand-int run-interval))))))
                        hosts)))))
