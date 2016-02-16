(ns puppetlabs.puppetdb.reports
  "Puppet report/event parsing

   Functions that handle conversion of reports from wire format to
   internal PuppetDB format, including validation."
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [com.rpl.specter :as sp]))

;; SCHEMA

(def event-wireformat-schema
  {:status s/Str
   :timestamp pls/Timestamp
   :property (s/maybe s/Str)
   :new_value (s/maybe pls/JSONable)
   :old_value (s/maybe pls/JSONable)
   :message (s/maybe s/Str)})

(def resource-wireformat-schema
  {:skipped s/Bool
   :timestamp pls/Timestamp
   :resource_type s/Str
   :resource_title s/Str
   :file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :containment_path [s/Str]
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
   :resources [resource-wireformat-schema]
   :noop (s/maybe s/Bool)
   :transaction_uuid (s/maybe s/Str)
   :catalog_uuid (s/maybe s/Str)
   :code_id (s/maybe s/Str)
   :cached_catalog_status (s/maybe s/Str)
   :metrics [metric-wireformat-schema]
   :logs [log-wireformat-schema]
   :environment s/Str
   :status (s/maybe s/Str)})

(def report-v6-wireformat-schema
  (-> report-wireformat-schema
      (dissoc :catalog_uuid :cached_catalog_status :code_id)))

(def resource-event-v5-wireformat-schema
  (-> resource-wireformat-schema
      (dissoc :skipped :events)
      (merge event-wireformat-schema)))

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
   :containing_class (s/maybe s/Str)
   :containment_path (s/maybe [s/Str])
   :property (s/maybe s/Str)
   :file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :status (s/maybe s/Str)
   :message (s/maybe s/Str)})

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
   (s/optional-key :noop) (s/maybe s/Bool)
   (s/optional-key :report_format) s/Int
   (s/optional-key :configuration_version) s/Str
   (s/optional-key :resources) (s/maybe resources-expanded-query-schema)
   (s/optional-key :metrics) metrics-expanded-query-schema
   (s/optional-key :logs) logs-expanded-query-schema
   (s/optional-key :resource_events) resource-events-expanded-query-schema
   (s/optional-key :transaction_uuid) (s/maybe s/Str)
   (s/optional-key :catalog_uuid) (s/maybe s/Str)
   (s/optional-key :code_id) (s/maybe s/Str)
   (s/optional-key :cached_catalog_status) (s/maybe s/Str)
   (s/optional-key :status) (s/maybe s/Str)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports Query -> Wire format conversions

(pls/defn-validated resource-events-query->wire-v5 :- [resource-event-v5-wireformat-schema]
  [resource-events :- resource-events-expanded-query-schema]
  (->> resource-events
       :data
       (map #(dissoc %
                     :report :certname :containing_class :configuration_version
                     :run_start_time :run_end_time :report_receive_time :environment))))

(defn report-query->wire-v5
  [report]
  (-> report
      (dissoc :hash :receive_time :resources)
      (update :resource_events resource-events-query->wire-v5)
      (update :metrics :data)
      (update :logs :data)))

(pls/defn-validated reports-query->wire-v5 :- [report-v5-wireformat-schema]
  [reports :- [report-query-schema]]
  (map report-query->wire-v5 reports))

(defn dash->underscore-report-keys [v5-report-or-older]
  (->> v5-report-or-older
       utils/dash->underscore-keys
       (sp/transform [:resource_events sp/ALL sp/ALL]
                     #(update % 0 utils/dashes->underscores))))

(defn- resource-event-v5->resource
  [resource-event]
  (-> resource-event
      (select-keys [:file :line :timestamp :resource_type :resource_title :containment_path])
      (assoc :skipped (= "skipped" (:status resource-event)))))

(defn resource-events-v5->resources
  [resource-events]
  (vec
   (for [[resource resource-events] (group-by resource-event-v5->resource resource-events)
         :let [events (mapv #(dissoc % :file :line :resource_type :resource_title :containment_path) resource-events)]]
     (assoc resource :events events))))

(defn wire-v6->wire-v7
  [{:keys [transaction_uuid] :as report}]
  (utils/assoc-when report
                    :catalog_uuid transaction_uuid
                    :cached_catalog_status nil
                    :code_id nil))

(defn wire-v5->wire-v7
  [report]
  (-> report
      (update :resource_events resource-events-v5->resources)
      (clojure.set/rename-keys {:resource_events :resources})
      wire-v6->wire-v7))

(defn wire-v4->wire-v7
  [report received-time]
  (-> report
      dash->underscore-report-keys
      (assoc :metrics nil
             :logs nil
             :noop nil
             :producer_timestamp received-time)
      wire-v5->wire-v7))


(defn wire-v3->wire-v7
  [report received-time]
  (-> report
      (assoc :status nil)
      (wire-v4->wire-v7 received-time)))

(pls/defn-validated report-query->wire-v7 :- report-wireformat-schema
  [report :- report-query-schema]
  (-> report
      report-query->wire-v5
      wire-v5->wire-v7))

(defn reports-query->wire-v7 [reports]
  (map report-query->wire-v7 reports))

(defn- resource->skipped-resource-events
  "Fabricate a skipped resource-event"
  [resource]
  (-> resource
      ;; We also need to grab the timestamp when the resource is `skipped'
      (select-keys [:resource_type :resource_title :file :line :containment_path :timestamp])
      (merge {:status "skipped" :property nil :old_value nil :new_value nil :message nil})
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
