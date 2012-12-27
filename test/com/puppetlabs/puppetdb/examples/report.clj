(ns com.puppetlabs.puppetdb.examples.report
  (:require [clj-time.coerce :as coerce]))

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :configuration-version  "a81jasj123"
    :start-time             "2011-01-01T12:00:00-03:00"
    :end-time               "2011-01-01T12:10:00-03:00"
    :resource-events
        [{:status           "success"
          :timestamp        "2011-01-01T12:00:01-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yo"
          :property         "message"
          :new-value        "notify, yo"
          :old-value        ["what" "the" "woah"]
          :message          "defined 'message' as 'notify, yo'"}
         {:status           "success"
          :timestamp        "2011-01-01T12:00:03-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yar"
          :property         "message"
          :new-value        {"absent" 5}
          :old-value        {"absent" true}
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
