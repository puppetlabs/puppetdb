(ns puppetlabs.puppetdb.query.reports
  (:require [clojure.set :as set]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

;; MUNGE

(pls/defn-validated rtj->event :- reports/resource-event-query-schema
  "Convert row_to_json format to real data."
  [event :- {s/Keyword s/Any}]
  (-> event
      (update :old_value json/parse-string)
      (update :new_value json/parse-string)))

(pls/defn-validated row->report
  "Convert a report query row into a final report format."
  [base-url :- s/Str]
  (fn [row]
    (-> row
        (utils/update-when [:resource_events] utils/child->expansion :reports :events base-url)
        (utils/update-when [:resource_events :data] (partial map rtj->event))
        (utils/update-when [:metrics] utils/child->expansion :reports :metrics base-url)
        (utils/update-when [:logs] utils/child->expansion :reports :logs base-url))))

(pls/defn-validated munge-result-rows
  "Reassemble report rows from the database into the final expected format."
  [version :- s/Keyword
   url-prefix :- s/Str]
  (let [base-url (str url-prefix "/" (name version))]
    (fn [rows]
      (map (row->report base-url) rows))))
