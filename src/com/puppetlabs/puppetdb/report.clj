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

(defn validate!
  "Validate a report data structure.  Throws IllegalArgumentException if
  the report is invalid."
  [report]
  (validate-against-model! Report report)
  (doseq [resource-event (:resource-events report)]
    (validate-against-model! ResourceEvent resource-event))
  report)
