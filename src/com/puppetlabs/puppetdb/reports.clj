;; ## Puppet report/event parsing
;;
;; Functions that handle conversion of reports from wire format to
;; internal PuppetDB format, including validation.

(ns com.puppetlabs.puppetdb.reports
  (:use [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.validation :only [defmodel validate-against-model!]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]])
  (:require [com.puppetlabs.cheshire :as json]
            [puppetlabs.kitchensink.core :as kitchensink]
            [clojure.string :as s]))

(defmodel Report
  {:certname                 :string
   :puppet-version           :string
   :report-format            :integer
   :configuration-version    :string
   :start-time               :datetime
   :end-time                 :datetime
   :resource-events          :coll
   :transaction-uuid         {:optional? true
                              :type      :string}
   :environment              {:optional? true
                              :type :string}
   :status                   {:optional? true
                              :type :string}})

(def report-fields
  "Report fields"
  (keys (:fields Report)))

(defmodel ResourceEvent
  {:status             :string
   :timestamp          :datetime
   :resource-type      :string
   :resource-title     :string
   :property           {:optional? true
                        :type      :string}
   :new-value          {:optional? true
                        :type      :jsonable}
   :old-value          {:optional? true
                        :type      :jsonable}
   :message            {:optional? true
                        :type      :string}
   :file               {:optional? true
                        :type      :string}
   :line               {:optional? true
                        :type      :integer}
   :containment-path   {:optional? true
                        :type      :coll}})

(def resource-event-fields
  "Resource event fields"
  (keys (:fields ResourceEvent)))

(def v2-new-event-fields [:file :line])

(defn validate-and-add-v2-event-field!
  [event field]
  {:pre [(kitchensink/seq-contains? v2-new-event-fields field)]}
  (if (contains? event field)
    (throw (IllegalArgumentException.
            (format
             "ResourceEvent has unknown keys: %s ('%s' command, version 1)"
             field (command-names :store-report)))))
  (assoc event field nil))

(defn validate-and-add-v2-event-fields!
  [event]
  (let [updated-event (reduce
                       validate-and-add-v2-event-field!
                       event
                       v2-new-event-fields)]
    (validate-against-model! ResourceEvent updated-event)
    updated-event))

(defmulti validate!
  "Validate a report data structure.  Throws IllegalArgumentException if
  the report is invalid."
  (fn [command-version _]
    command-version))

(defmethod validate! 1
  [_ report]
  (validate-against-model! Report report)
  (assoc report :resource-events
         (mapv validate-and-add-v2-event-fields! (:resource-events report))))

(defmethod validate! 2
  [_ report]
  (validate-against-model! Report report)
  (doseq [resource-event (:resource-events report)]
    (validate-against-model! ResourceEvent resource-event)
    (if (not-every? string? (resource-event :containment-path))
      (throw (IllegalArgumentException.
              (format "Containment path should only contain strings: '%s'"
                      (resource-event :containment-path))))))
  report)

(defmethod validate! 3
  [_ report]
  (validate! 2 report)
  (when (nil? (:environment report))
    (throw (IllegalArgumentException. "Version 3 of reports must contain an environment")))
  report)

(defmethod validate! 4
  [_ report]
  (validate! 2 report)
  (when (nil? (:environment report))
    (throw (IllegalArgumentException. "Version 4 of reports must contain an environment")))
  (when (nil? (:status report))
    (throw (IllegalArgumentException. "Version 4 of reports must contain a status")))
  report)

(defn sanitize-events
  "This function takes an array of events and santizes them, ensuring only
   valid keys are returned."
  [events]
  {:pre [(coll? events)]
   :post [(coll? %)]}
  (let [valid-keys (map name resource-event-fields)]
    (for [event events]
      (select-keys event valid-keys))))

(defn sanitize-report
  "This function takes a report and sanitizes it, ensuring only valid data
   is left over."
  [payload]
  {:pre [(map? payload)]
   :post [(map? %)]}
  (let [valid-keys (map name report-fields)]
    (-> payload
        (select-keys valid-keys)
        (update-in ["resource-events"] sanitize-events))))
