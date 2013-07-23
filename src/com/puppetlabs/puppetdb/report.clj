;; ## Puppet report/event parsing
;;
;; Functions that handle conversion of reports from wire format to
;; internal PuppetDB format, including validation.

(ns com.puppetlabs.puppetdb.report
  (:use [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.validation :only [defmodel validate-against-model!]]
        [com.puppetlabs.puppetdb.command.constants :only [command-names]])
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [clojure.string :as s]))

(defmodel Report
  {:certname                 :string
   :puppet-version           :string
   :report-format            :integer
   :configuration-version    :string
   :start-time               :datetime
   :end-time                 :datetime
   :resource-events          :coll})

(defmodel ResourceEvent
  {:status             :string
   :timestamp          :datetime
   :resource-type      :string
   :resource-title     :string
   :property           { :optional? true
                         :type      :string}
   :new-value          { :optional? true
                         :type      :jsonable }
   :old-value          { :optional? true
                         :type      :jsonable }
   :message            { :optional? true
                         :type      :string}})

;; TODO: update this as we add new fields
;; TODO: docs
(def v2-new-event-fields [])

(defn validate-and-add-v2-event-field!
  ;; TODO: docs
  [event field]
  (if (contains? event field)
    (throw (IllegalArgumentException.
             (format
               "ResourceEvent has unknown keys: %s ('%s' command, version 1)"
               field (command-names :store-report)))))
  (assoc event field nil))

(defn validate-and-add-v2-event-fields!
  ;; TODO: docs
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
    (validate-against-model! ResourceEvent resource-event))
  report)
