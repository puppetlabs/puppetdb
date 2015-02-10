(ns puppetlabs.puppetdb.reports
  "Puppet report/event parsing

   Functions that handle conversion of reports from wire format to
   internal PuppetDB format, including validation."
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.validation :refer [defmodel validate-against-model!]]))

(defmodel Report
  {:certname                 :string
   :puppet_version           :string
   :report_format            :integer
   :configuration_version    :string
   :start_time               :datetime
   :end_time                 :datetime
   :resource_events          :coll
   :transaction_uuid         {:optional? true
                              :type      :string}
   :environment              {:optional? true
                              :type :string}
   :status                   {:optional? true
                              :type :string}
   })

(def report-fields
  "Report fields"
  (keys (:fields Report)))

(defmodel ResourceEvent
  {:status             :string
   :timestamp          :datetime
   :resource_type      :string
   :resource_title     :string
   :property           {:optional? true
                        :type      :string}
   :new_value          {:optional? true
                        :type      :jsonable}
   :old_value          {:optional? true
                        :type      :jsonable}
   :message            {:optional? true
                        :type      :string}
   :file               {:optional? true
                        :type      :string}
   :line               {:optional? true
                        :type      :integer}
   :containment_path   {:optional? true
                        :type      :coll}
   })

(def resource-event-fields
  "Resource event fields"
  (keys (:fields ResourceEvent)))

(defmulti validate!
  "Validate a report data structure.  Throws IllegalArgumentException if
  the report is invalid."
  (fn [command-version _]
    command-version))

(defmethod validate! 5
  [_ report]
  (validate-against-model! Report report)
  (doseq [resource-event (:resource_events report)]
    (validate-against-model! ResourceEvent resource-event)
    (if (not-every? string? (resource-event :containment_path))
      (throw (IllegalArgumentException.
              (format "Containment path should only contain strings: '%s'"
                      (resource-event :containment_path))))))
  (when (nil? (:environment report))
    (throw (IllegalArgumentException. "Version 5 of reports must contain an environment")))
  (when (nil? (:status report))
    (throw (IllegalArgumentException. "Version 5 of reports must contain a status")))
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
        (update-in ["resource_events"] sanitize-events))))
