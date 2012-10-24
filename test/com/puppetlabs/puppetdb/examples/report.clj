(ns com.puppetlabs.puppetdb.examples.report
  (:require [clj-time.coerce :as coerce]))

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :configuration-version  "123456789"
    :start-time             "2011-01-01T12:00:00-03:00"
    :end-time               "2011-01-01T12:10:00-03:00"
    :description            "My description here"
    :resource-events
        [{:status           "success"
          :timestamp        "2011-01-01T12:00:01-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yo"
          :property         "message"
          :new-value        "notify, yo"
          :old-value        "absent"
          :message          "defined 'message' as 'notify, yo'"}
         {:status           "skipped"
          :timestamp        "2011-01-01T12:00:02-03:00"
          :resource-type    "Notify"
          :resource-title   "hi"
          :property         nil
          :new-value        nil
          :old-value        nil
          :message          nil}]
          }})
