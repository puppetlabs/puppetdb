(ns puppetlabs.puppetdb.reports
  "Puppet report/event parsing

   Functions that handle conversion of reports from wire format to
   internal PuppetDB format, including validation."
  (:require [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls])
  (:import  (org.postgresql.util PGobject)))

(def resource-event-schema
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

(def metric-schema
  {:category String
   :name String
   :value s/Num})

(def log-schema
  {:file (s/maybe String)
   :line (s/maybe s/Int)
   :level String
   :message String
   :source String
   :tags [String]
   :time pls/Timestamp})

(def report-schema
  {:certname                 s/Str
   :puppet_version           s/Str
   :report_format            s/Int
   :configuration_version    s/Str
   :start_time               pls/Timestamp
   :end_time                 pls/Timestamp
   :resource_events          [resource-event-schema]
   :noop                     (s/maybe s/Bool)
   :transaction_uuid         (s/maybe s/Str)
   :metrics                  (s/maybe [metric-schema])
   :logs                     (s/maybe [log-schema])
   :environment              s/Str
   :status                   s/Str})

(pls/defn-validated sanitize-events :- [resource-event-schema]
  "This function takes an array of events and santizes them, ensuring only
   valid keys are returned."
  [events :- (s/pred coll? 'coll?)]
  (for [event events]
    (pls/strip-unknown-keys resource-event-schema event)))

(pls/defn-validated sanitize-report :- report-schema
  "This function takes a report and sanitizes it, ensuring only valid data
   is left over."
  [payload :- (s/pred map? 'map?)]
  (-> (pls/strip-unknown-keys report-schema payload)
      (update-in [:resource_events] sanitize-events)))
