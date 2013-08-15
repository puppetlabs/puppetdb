;; ## Report generation and manipulation
;;
;; A suite of functions that aid in constructing random reports, or
;; randomly modifying existing reports

(ns com.puppetlabs.puppetdb.report.utils
  (:require [clojure.string :as string]
            [clojure.walk :refer [keywordize-keys]]
            [com.puppetlabs.random :refer [random-string random-resource-event]]))

(defn add-random-event-to-report
  "Add a randomly-generated event to an existing report."
  [{:keys [resource-events] :as report}]
  (let [new-event (random-resource-event)]
    (assoc report :resource-events (conj resource-events new-event))))

(defn mod-event-in-report
  "Randomly select a resource event in an existing report, and randomly modify
  its metadata."
  [{:keys [resource-events] :as report}]
  (let [i               (rand-int (count resource-events))
        modified-event  (-> (resource-events i)
                            (assoc :timestamp (random-string))
                            (assoc :status    (random-string))
                            (assoc :old-value (random-string))
                            (assoc :new-value (random-string))
                            (assoc :message   (random-string)))]
    (assoc report :resource-events (assoc resource-events i modified-event))))

(defn remove-random-event-from-report
  "Randomly select an event from an existing report and remove it."
  [{:keys [resource-events] :as report}]
  (let [len (count resource-events)
        i   (rand-int len)]
    (assoc report :resource-events
        (concat (subvec resource-events 0 i)
                (subvec resource-events (inc i) len)))))

