;; ## Benchmark suite
;;
;; This command-line utility will simulate catalog submission for a
;; population. It requires that a separate, running instance of
;; Grayskull for it to submit catalogs to.
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
;; Grayskull.
;;
;; ### Main loop
;;
;; The main loop is written in the form of a wall-clock
;; simulator. Each run through the main loop, we send each agent an
;; `update` message with the current wall-clock. Each agent decides
;; independently whether or not to submit a catalog during that clock
;; tick.
;;
(ns com.puppetlabs.cmdb.cli.benchmark
  (:import (java.io File))
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.cmdb.catalog.utils :as catutils]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.util :as util]
            [fs.core :as fs])
  (:use [com.puppetlabs.utils :only (cli! ini-to-map utf8-string->sha1)]
        [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]))

(def hosts nil)
(def rest-url nil)
(def runinterval nil)
(def rand-percentage 0)

(defn submit-catalog
  "Send the given wire-format catalog (associated with `host`) to a
  command-processing endpoint."
  [host catalog]
  (let [msg    (-> {:command "replace catalog"
                    :version 1
                    :payload (json/generate-string catalog)}
                   (json/generate-string))
        body   (format "checksum=%s&payload=%s"
                       (utf8-string->sha1 msg)
                       (util/url-encode msg))
        result (client/post rest-url {:body               body
                                      :throw-exceptions   false
                                      :content-type       :x-www-form-urlencoded
                                      :character-encoding "UTF-8"
                                      :accept             :json})]
    (if (not= 200 (:status result))
      (log/error result))))

(defn tweak-catalog
  "Slightly tweak the given catalog, returning a new catalog"
  [catalog]
  (catutils/add-random-resource-to-wire-catalog catalog))

(defn update-host
  "Send a new _clock tick_ to a host

  On each tick, a host:

  * Determines if its time to submit a new catalog (by looking at the
    time it last sent one, and comparing that to the desired
    `runinterval`

  * If we need to submit a catalog, optionally tweak the catalog (a
    hosts catalog changes in accordance to the users preference)

  * Submit the resulting catalog"
  [{:keys [host lastrun catalog] :as state} clock]
  (if (> (- clock lastrun) runinterval)
    (let [catalog (if (< (rand 100) rand-percentage)
                    (tweak-catalog catalog)
                    catalog)]
      ;; Submit the catalog in a separate thread, so as to not disturb
      ;; the world-loop and otherwise distort the space-time
      ;; continuum.
      (future
        (log/info (format "[%s] submitting catalog" host))
        (submit-catalog host catalog))
      (assoc state :lastrun clock :catalog catalog))
    state))

(defn world-loop
  "Sends out new _clock tick_ messages to all agents.

  This function never terminates.

  The time resolution of this loop is 10ms."
  []
  (loop [last-time (System/currentTimeMillis)]
    (let [curr-time (System/currentTimeMillis)]

      ;; Send out updated ticks to each agent
      (doseq [host hosts]
        (send host update-host curr-time))

      (Thread/sleep 10)
      (recur curr-time))))

(defn associate-catalog-with-host
  "Takes the given catalog and transforms it to appear related to
  `hostname`"
  [hostname catalog]
  (-> catalog
      (assoc-in ["data" "name"] hostname)
      (assoc-in ["data" "resources"] (for [resource (get-in catalog ["data" "resources"])]
                                       (assoc resource "tags" (conj (resource "tags") hostname))))))
(defn -main
  [& args]
  (let [[options _] (cli! args
                          ["-d" "--dir" "Path to a directory containing sample JSON catalogs (files must end with .json)"]
                          ["-u" "--url" "URL to REST endpoint for commands"]
                          ["-i" "--runinterval" "What runinterval (in minutes) to use during simulation"]
                          ["-n" "--numhosts" "How many hosts to use during simulation"]
                          ["-rp" "--rand-perc" "What percentage of submitted catalogs are tweaked (int between 0 and 100)"])

        dir         (:dir options)
        catalogs    (->> (for [file (fs/glob (fs/file dir "*.json"))]
                           (try
                             (json/parse-string (slurp file))
                             (catch Exception e
                               (log/error (format "Error parsing %s; skipping" file)))))
                         (remove nil?)
                         (into []))

        nhosts      (:numhosts options)
        hostnames   (into #{} (map #(str "host-" %) (range 1 (Integer/parseInt nhosts))))]

    (def rest-url (:url options))
    (def rand-percentage (Integer/parseInt (:rand-perc options)))
    (def runinterval (* 60 1000 (Integer/parseInt (:runinterval options))))

    ;; Create an agent for each host
    (def hosts
      (into [] (map #(agent {:host %,
                             :lastrun (- (System/currentTimeMillis) (rand-int runinterval)),
                             :catalog (associate-catalog-with-host % (rand-nth catalogs))})
                    hostnames)))

    ;; Loop forever
    (world-loop)))
