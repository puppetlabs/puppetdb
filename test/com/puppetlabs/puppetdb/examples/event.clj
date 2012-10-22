(ns com.puppetlabs.puppetdb.examples.event
  (:require [clj-time.coerce :as coerce]))

(def resource-event-groups
  {:basic
   {:group-id         "basic-resource-event-group"
    :start-time       "2011-01-01T12:00:00-03:00"
    :end-time         "2011-01-01T12:10:00-03:00"
    :resource-events
        [{:certname         "foo.local"
          :status           "success"
          :timestamp        "2011-01-01T12:00:01-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yo"
          :property-name    "message"
          :property-value   "notify, yo"
          :previous-value   "absent"
          :message          "defined 'message' as 'notify, yo'"}
         {:certname         "foo.local"
          :status           "skipped"
          :timestamp        "2011-01-01T12:00:02-03:00"
          :resource-type    "Notify"
          :resource-title   "hi"
          :property-name    nil
          :property-value   nil
          :previous-value   nil
          :message          nil}]
          }})
