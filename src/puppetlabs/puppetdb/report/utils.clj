(ns puppetlabs.puppetdb.report.utils
  "Report generation and manipulation

   A suite of functions that aid in constructing random reports, or
   randomly modifying existing reports"
  (:require [puppetlabs.puppetdb.random :refer [random-string random-resource-event]]))

(defn add-random-event-to-report
  "Add a randomly-generated event to an existing report."
  [{:keys [resource_events] :as report}]
  (let [new-event (random-resource-event)]
    (assoc report :resource_events (conj resource_events new-event))))

(defn mod-event-in-report
  "Randomly select a resource event in an existing report, and randomly modify
  its metadata."
  [{:keys [resource_events] :as report}]
  (let [i               (rand-int (count resource_events))
        modified-event  (-> (resource_events i)
                            (assoc :timestamp (random-string))
                            (assoc :status    (random-string))
                            (assoc :old_value (random-string))
                            (assoc :new_value (random-string))
                            (assoc :message   (random-string)))]
    (assoc report :resource_events (assoc resource_events i modified-event))))

(defn remove-random-event-from-report
  "Randomly select an event from an existing report and remove it."
  [{:keys [resource_events] :as report}]
  (let [len (count resource_events)
        i   (rand-int len)]
    (assoc report :resource_events
           (concat (subvec resource_events 0 i)
                   (subvec resource_events (inc i) len)))))
