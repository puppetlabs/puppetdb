(ns puppetlabs.puppetdb.reports
  "Puppet report/event parsing

   Functions that handle conversion of reports from wire format to
   internal PuppetDB format, including validation."
  (:require [schema.core :as s]
            [clojure.set :as set]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [com.rpl.specter :as sp]))

;; SCHEMA

(def event-wireformat-schema
  {:status s/Str
   :corrective_change (s/maybe s/Bool)
   :timestamp pls/Timestamp
   :property (s/maybe s/Str)
   :new_value (s/maybe pls/JSONable)
   :old_value (s/maybe pls/JSONable)
   :message (s/maybe s/Str)
   (s/optional-key :name) (s/maybe s/Str)})

(def resource-wireformat-schema
  {:skipped s/Bool
   :timestamp pls/Timestamp
   :resource_type s/Str
   :resource_title s/Str
   :file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :containment_path [s/Str]
   :corrective_change (s/maybe s/Bool)
   :events [event-wireformat-schema]})

(def metric-wireformat-schema
  {:category s/Str
   :name s/Str
   :value s/Num})

(def log-wireformat-schema
  {:file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :level s/Str
   :message s/Str
   :source s/Str
   :tags [s/Str]
   :time pls/Timestamp})

(def report-wireformat-schema
  {:certname s/Str
   :puppet_version s/Str
   :report_format s/Int
   :configuration_version s/Str
   :start_time pls/Timestamp
   :end_time pls/Timestamp
   :producer_timestamp pls/Timestamp
   :producer (s/maybe s/Str)
   :corrective_change (s/maybe s/Bool)
   :resources [resource-wireformat-schema]
   :noop (s/maybe s/Bool)
   :noop_pending (s/maybe s/Bool)
   :transaction_uuid (s/maybe s/Str)
   :catalog_uuid (s/maybe s/Str)
   :code_id (s/maybe s/Str)
   (s/optional-key :job_id) (s/maybe s/Str)
   :cached_catalog_status (s/maybe s/Str)
   :metrics [metric-wireformat-schema]
   :logs [log-wireformat-schema]
   :environment s/Str
   :status (s/maybe s/Str)
   (s/optional-key :type) (s/enum "agent" "plan")})

(defn update-resource-events
  [update-fn]
  (fn [resource]
    (-> resource
        update-fn
        (update :events #(mapv update-fn %)))))

(def report-v7-wireformat-schema
  (let [update-fn #(dissoc % :corrective_change :name)]
    (-> report-wireformat-schema
        (dissoc :producer :noop_pending :corrective_change :job_id :type)
        (update :resources #(mapv (update-resource-events update-fn) %)))))

(def report-v6-wireformat-schema
  (-> report-v7-wireformat-schema
      (dissoc :catalog_uuid :cached_catalog_status :code_id)))

(def resource-event-v5-wireformat-schema
  (-> resource-wireformat-schema
      (dissoc :skipped :events)
      (merge event-wireformat-schema)
      (dissoc :corrective_change)))

(def report-v5-wireformat-schema
  (-> report-v6-wireformat-schema
      (dissoc :resources)
      (assoc :resource_events [resource-event-v5-wireformat-schema])))

(def resource-event-v4-wireformat-schema
  (utils/underscore->dash-keys resource-event-v5-wireformat-schema))

(def report-v4-wireformat-schema
  (-> report-v5-wireformat-schema
      utils/underscore->dash-keys
      (assoc :resource-events [resource-event-v4-wireformat-schema])
      (dissoc :logs :metrics :noop :producer-timestamp)))

(def report-v3-wireformat-schema
  (dissoc report-v4-wireformat-schema :status))

(def resource-event-query-schema
  {(s/optional-key :certname) s/Str
   (s/optional-key :report_receive_time) pls/Timestamp
   (s/optional-key :run_end_time) pls/Timestamp
   (s/optional-key :environment) s/Str
   (s/optional-key :configuration_version) s/Str
   (s/optional-key :run_start_time) pls/Timestamp
   (s/optional-key :report) s/Str
   :new_value s/Any
   :old_value s/Any
   :resource_title s/Str
   :resource_type s/Str
   :timestamp pls/Timestamp
   :corrective_change (s/maybe s/Bool)
   :containing_class (s/maybe s/Str)
   :containment_path (s/maybe [s/Str])
   :property (s/maybe s/Str)
   :file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :status (s/maybe s/Str)
   :message (s/maybe s/Str)
   (s/optional-key :name) (s/maybe s/Str)})

(def resource-events-expanded-query-schema
  {:href s/Str
   (s/optional-key :data) [resource-event-query-schema]})

(def metrics-expanded-query-schema
  {:href s/Str
   (s/optional-key :data) [metric-wireformat-schema]})

(def resources-expanded-query-schema
  {:href s/Str
   (s/optional-key :data) [resource-wireformat-schema]})

(def logs-expanded-query-schema
  {:href s/Str
   (s/optional-key :data) [log-wireformat-schema]})

(def report-query-schema
  {(s/optional-key :hash) s/Str
   (s/optional-key :environment) (s/maybe s/Str)
   (s/optional-key :certname) s/Str
   (s/optional-key :puppet_version) s/Str
   (s/optional-key :receive_time) pls/Timestamp
   (s/optional-key :start_time) pls/Timestamp
   (s/optional-key :end_time) pls/Timestamp
   (s/optional-key :producer_timestamp) pls/Timestamp
   (s/optional-key :producer) (s/maybe s/Str)
   (s/optional-key :corrective_change) (s/maybe s/Bool)
   (s/optional-key :noop) (s/maybe s/Bool)
   (s/optional-key :noop_pending) (s/maybe s/Bool)
   (s/optional-key :report_format) s/Int
   (s/optional-key :configuration_version) s/Str
   (s/optional-key :resources) (s/maybe resources-expanded-query-schema)
   (s/optional-key :metrics) metrics-expanded-query-schema
   (s/optional-key :logs) logs-expanded-query-schema
   (s/optional-key :resource_events) resource-events-expanded-query-schema
   (s/optional-key :transaction_uuid) (s/maybe s/Str)
   (s/optional-key :catalog_uuid) (s/maybe s/Str)
   (s/optional-key :code_id) (s/maybe s/Str)
   (s/optional-key :job_id) (s/maybe s/Str)
   (s/optional-key :cached_catalog_status) (s/maybe s/Str)
   (s/optional-key :status) (s/maybe s/Str)
   (s/optional-key :type) (s/enum "agent" "plan")})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports Query -> Wire format conversions

(pls/defn-validated resource-events-query->wire-v5 :- [resource-event-v5-wireformat-schema]
  [resource-events :- resource-events-expanded-query-schema]
  (->> resource-events
       :data
       (map #(dissoc %
                     :report :certname :containing_class :configuration_version
                     :run_start_time :run_end_time :report_receive_time :environment
                     :corrective_change))))

(pls/defn-validated munge-resource-events-for-v8
  [resource-events :- (s/maybe resource-events-expanded-query-schema)]
  (->> resource-events
       :data
       (map #(dissoc %
                     :report :certname :containing_class :configuration_version
                     :run_start_time :run_end_time :report_receive_time :environment))))

(defn generic-query->wire-transform
  "Dissociate query-only and pe-only fields and replace href fields with the
   corresponding data key."
  [report]
  (-> report
      (dissoc :hash :receive_time :resources)
      (update :metrics :data)
      (update :logs :data)))

(defn report-query->wire-v5
  [report]
  (-> report
      generic-query->wire-transform
      (dissoc :noop_pending :corrective_change :producer :catalog_uuid :cached_catalog_status :code_id :job_id)
      (update :resource_events resource-events-query->wire-v5)))

(pls/defn-validated reports-query->wire-v5 :- [report-v5-wireformat-schema]
  [reports :- [report-query-schema]]
  (map report-query->wire-v5 reports))

(defn dash->underscore-report-keys [v5-report-or-older]
  (->> v5-report-or-older
       utils/dash->underscore-keys
       (sp/transform [:resource_events sp/ALL sp/ALL]
                     #(update % 0 utils/dashes->underscores))))

(defn- resource-event->resource
  [resource-event]
  (-> resource-event
      (select-keys [:file :line :timestamp :resource_type :resource_title :containment_path])
      (assoc :skipped (= "skipped" (:status resource-event)))))

(defn resource-events-wire->resources
  [resource-events]
  (vec
    (for [[resource resource-events] (group-by resource-event->resource resource-events)
          :let [events (mapv #(-> %
                                  (dissoc :file :line :resource_type
                                          :resource_title :containment_path)
                                  (utils/assoc-when :corrective_change nil))
                             resource-events)]]
      (-> resource
          (assoc :events events)
          (utils/assoc-when :corrective_change nil)))))

(defn wire-v7->wire-v8
  [report]
  (let [update-fn #(assoc % :corrective_change nil)]
    (-> report
        (utils/assoc-when :noop_pending nil :corrective_change nil :producer nil :job_id nil)
        (update :resources #(mapv (update-resource-events update-fn) %)))))

(defn wire-v6->wire-v8
  [{:keys [transaction_uuid] :as report}]
  (-> report
    (utils/assoc-when :catalog_uuid transaction_uuid
                      :cached_catalog_status nil
                      :code_id nil)
    wire-v7->wire-v8))

(defn wire-v5->wire-v8
  [report]
  (-> report
      (update :resource_events resource-events-wire->resources)
      (set/rename-keys {:resource_events :resources})
      wire-v6->wire-v8))

(defn wire-v4->wire-v8
  [report received-time]
  (-> report
      dash->underscore-report-keys
      (assoc :metrics nil
             :logs nil
             :noop nil
             :producer_timestamp received-time)
      wire-v5->wire-v8))

(defn wire-v3->wire-v8
  [report received-time]
  (-> report
      (assoc :status nil)
      (wire-v4->wire-v8 received-time)))

(pls/defn-validated report-query->wire-v8 :- report-wireformat-schema
  [report :- report-query-schema]
  (-> report
      generic-query->wire-transform
      (update :resource_events (comp resource-events-wire->resources
                                     munge-resource-events-for-v8))
      (set/rename-keys {:resource_events :resources})))

(defn reports-query->wire-v8 [reports]
  (map report-query->wire-v8 reports))

(defn- resource->skipped-resource-events
  "Fabricate a skipped resource-event"
  [resource]
  (-> resource
      ;; We also need to grab the timestamp when the resource is `skipped'
      (select-keys [:resource_type :resource_title :file :line :containment_path :timestamp :name])
      (merge {:status "skipped" :property nil :old_value nil :new_value nil :message nil :corrective_change false})
      vector))

(defn- resource->resource-events
  [{:keys [skipped] :as resource}]
  (cond
    (= skipped true)
    (resource->skipped-resource-events resource)

    ;; If we get an unchanged resource, disregard it
    (empty? (:events resource))
    []

    :else
    (let [resource-metadata (select-keys resource [:resource_type :resource_title :file :line :containment_path])]
      (map (partial merge resource-metadata) (:events resource)))))

(defn resources->resource-events
  [resources]
  (->> resources
       (mapcat resource->resource-events)
       vec))
