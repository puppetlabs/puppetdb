(ns puppetlabs.puppetdb.reports
  "Puppet report/event parsing

   Functions that handle conversion of reports from wire format to
   internal PuppetDB format, including validation."
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [com.rpl.specter :as sp]))

;; SCHEMA

(def resource-event-wireformat-schema
  {:status             s/Str
   :timestamp          pls/Timestamp
   :resource_type      s/Str
   :resource_title     s/Str
   :property           (s/maybe s/Str)
   :new_value          (s/maybe pls/JSONable)
   :old_value          (s/maybe pls/JSONable)
   :message            (s/maybe s/Str)
   :file               (s/maybe s/Str)
   :line               (s/maybe s/Int)
   :containment_path   [s/Str]})

(def resource-event-v4-wireformat-schema
  (utils/underscore->dash-keys resource-event-wireformat-schema))

(def metric-schema
  {:category s/Str
   :name s/Str
   :value s/Num})

(def log-schema
  {:file (s/maybe s/Str)
   :line (s/maybe s/Int)
   :level s/Str
   :message s/Str
   :source s/Str
   :tags [s/Str]
   :time pls/Timestamp})

(def report-wireformat-schema
  {:certname                 s/Str
   :puppet_version           s/Str
   :report_format            s/Int
   :configuration_version    s/Str
   :start_time               pls/Timestamp
   :end_time                 pls/Timestamp
   :producer_timestamp       pls/Timestamp
   :resource_events          [resource-event-wireformat-schema]
   :noop                     (s/maybe s/Bool)
   :transaction_uuid         (s/maybe s/Str)
   :metrics                  (s/maybe [metric-schema])
   :logs                     (s/maybe [log-schema])
   :environment              s/Str
   :status                   (s/maybe s/Str)})

(def report-v4-wireformat-schema
  (-> report-wireformat-schema
      utils/underscore->dash-keys
      (assoc :resource-events [resource-event-v4-wireformat-schema])
      (dissoc :logs :metrics :noop :producer-timestamp)))

(def report-v3-wireformat-schema
  (dissoc report-v4-wireformat-schema :status))

(pls/defn-validated sanitize-events :- [resource-event-wireformat-schema]
  "This function takes an array of events and santizes them, ensuring only
   valid keys are returned."
  [events :- (s/pred coll? 'coll?)]
  (for [event events]
    (pls/strip-unknown-keys resource-event-wireformat-schema event)))

(pls/defn-validated sanitize-report :- report-wireformat-schema
  "This function takes a report and sanitizes it, ensuring only valid data
   is left over."
  [payload :- (s/pred map? 'map?)]
  (-> (pls/strip-unknown-keys report-wireformat-schema payload)
      (update-in [:resource_events] sanitize-events)))

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
   (s/optional-key :data) [metric-schema]})

(def logs-expanded-query-schema
  {:href s/Str
   (s/optional-key :data) [log-schema]})

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
   (s/optional-key :metrics) metrics-expanded-query-schema
   (s/optional-key :logs) logs-expanded-query-schema
   (s/optional-key :resource_events) resource-events-expanded-query-schema
   (s/optional-key :transaction_uuid) s/Str
   (s/optional-key :status) (s/maybe s/Str)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Reports Query -> Wire format conversions

(pls/defn-validated event-query->wire-v5 :- resource-event-wireformat-schema
  [event :- resource-event-query-schema]
  (-> event
      (dissoc :report :certname :containing_class :configuration_version
              :run_start_time :run_end_time :report_receive_time :environment)))

(pls/defn-validated events-expanded->wire-v5 :- [resource-event-wireformat-schema]
  [events :- resource-events-expanded-query-schema]
  (sort-by
   #(mapv % [:timestamp :resource_type :resource_title :property])
   (map event-query->wire-v5
        (:data events))))

(pls/defn-validated logs-query->wire-v5 :- [log-schema]
  [logs :- logs-expanded-query-schema]
  (:data logs))

(pls/defn-validated metrics-query->wire-v5 :- [metric-schema]
  [metrics :- metrics-expanded-query-schema]
  (:data metrics))

(pls/defn-validated report-query->wire-v5 :- report-wireformat-schema
  [report :- report-query-schema]
  (-> report
      (dissoc :hash :receive_time)
      (update-in [:resource_events] events-expanded->wire-v5)
      (update-in [:metrics] metrics-query->wire-v5)
      (update-in [:logs] logs-query->wire-v5)))

(pls/defn-validated reports-query->wire-v5
  [reports :- [report-query-schema]]
  (map report-query->wire-v5
       reports))

(defn dash->underscore-report-keys [report]
  (->> report
       utils/dash->underscore-keys
       (sp/transform [:resource_events sp/ALL sp/ALL]
                     #(update % 0 utils/dashes->underscores))))

(pls/defn-validated wire-v4->wire-v5
  [report received-time]
  (-> report
      dash->underscore-report-keys
      (assoc :metrics nil
             :logs nil
             :noop nil
             :producer_timestamp received-time)))

(pls/defn-validated wire-v3->wire-v5
  [report received-time]
  (-> report
      (assoc :status nil)
      (wire-v4->wire-v5 received-time)))
