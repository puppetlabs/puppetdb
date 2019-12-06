(ns puppetlabs.puppetdb.examples.reports
  (:require [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.utils :as utils]
            [schema.core :as s]))

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet_version         "3.0.1"
    :report_format          4
    :transaction_uuid       "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :configuration_version  "a81jasj123"
    :start_time             "2011-01-01T12:00:00-03:00"
    :end_time               "2011-01-01T12:10:00-03:00"
    :producer_timestamp     "2011-01-01T12:11:00-03:00"
    :producer               "bar.com"
    :catalog_uuid "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :code_id nil
    :job_id nil
    :cached_catalog_status "not_used"
    :environment            "DEV"
    :status                 "unchanged"
    :noop                   false
    :noop_pending           true
    :corrective_change      true
    :logs
    {:href ""
     :data
     [{:file nil,
       :line nil,
       :level "info",
       :message "Caching catalog for mbp.local",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.321565000-08:00"}
      {:file nil,
       :line nil,
       :level "info",
       :message "Applying configuration version '1424992544'",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.388965000-08:00"}]}
    :metrics
    {:href ""
     :data
     [{:category  "resources"
       :name  "changed"
       :value  3.14}
      {:category  "resources"
       :name  "failed"
       :value  2.71}
      {:category  "resources"
       :name  "failed_to_restart"
       :value  0}]}
    :resource_events
    {:href ""
     :data
     [{:certname "foo.local"
       :status "success"
       :timestamp "2011-01-01T12:00:01-03:00"
       :resource_type "Notify"
       :resource_title "notify, yo"
       :environment "DEV"
       :property "message"
       :name "foo"
       :new_value "notify, yo"
       :old_value ["what" "the" "woah"]
       :corrective_change true
       :message "defined 'message' as 'notify, yo'"
       :file "foo.pp"
       :line 1
       :containment_path nil
       :containing_class nil}
      {:certname "foo.local"
       :status "failure"
       :timestamp "2011-01-01T12:00:03-03:00"
       :resource_type "Notify"
       :environment "DEV"
       :corrective_change false
       :resource_title "notify, yar"
       :property "message"
       :name "bar"
       :new_value {"absent" 5}
       :old_value {"absent" true}
       :message "defined 'message' as 'notify, yo'"
       :file nil
       :line nil
       :containment_path []
       :containing_class nil}
      {:certname "foo.local"
       :status "skipped"
       :timestamp "2011-01-01T12:00:02-03:00"
       :environment "DEV"
       :resource_type "Notify"
       :resource_title "hi"
       :corrective_change false
       :property nil
       :name nil
       :new_value nil
       :old_value nil
       :message nil
       :file "bar"
       :line 2
       :containment_path ["Foo" "" "Bar[Baz]"]
       :containing_class "Foo"}]}}

   :basic2
   {:certname "foo.local"
    :puppet_version "3.0.1"
    :report_format 4
    :transaction_uuid "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :catalog_uuid "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :code_id nil
    :job_id nil
    :cached_catalog_status "not_used"
    :configuration_version "bja3985a23"
    :start_time "2013-08-28T19:00:00-03:00"
    :end_time "2013-08-28T19:10:00-03:00"
    :producer_timestamp "2013-08-28T19:11:00-03:00"
    :producer "bar.com"
    :environment "DEV"
    :corrective_change false
    :status "unchanged"
    :noop true
    :noop_pending true
    :logs
    {:href ""
     :data
     [{:file nil,
        :line nil,
        :level "info",
        :message "Caching catalog for mbp.local",
        :source "//mbp.local/Puppet",
        :tags [ "info" ],
        :time "2015-02-26T15:20:17.321565000-08:00"}
       {:file nil,
        :line nil,
        :level "info",
        :message "Applying configuration version '1424992544'",
        :source "//mbp.local/Puppet",
        :tags [ "info" ],
        :time "2015-02-26T15:20:17.388965000-08:00"}]}
    :metrics
    {:href ""
     :data
     [{:category  "resources"
       :name  "changed"
       :value  3.14}
      {:category  "resources"
       :name  "failed"
       :value  2.71}
      {:category  "resources"
       :name  "failed_to_restart"
       :value  0}]}
    :resource_events
    {:href ""
     :data
     [{:certname "foo.local"
       :status "success"
       :timestamp "2013-08-28T19:36:34.000Z"
       :resource_type "Notify"
       :resource_title "Creating tmp directory at /Users/foo/tmp"
       :property "message"
       :name "foo"
       :new_value "Creating tmp directory at /Users/foo/tmp"
       :old_value "absent"
       :corrective_change false
       :message "defined 'message' as 'Creating tmp directory at /Users/foo/tmp'"
       :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
       :line 8
       :containment_path nil
       :containing_class nil}
      {:certname "foo.local"
       :status "success"
       :timestamp "2013-08-28T17:55:45.000Z"
       :resource_type "File"
       :resource_title "puppet-managed-file"
       :property "ensure"
       :name "bar"
       :new_value "present"
       :old_value "absent"
       :corrective_change false
       :message "created"
       :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
       :line 17
       :containment_path []
       :containing_class nil}
      {:certname "foo.local"
       :status "success"
       :timestamp "2013-08-28T17:55:45.000Z"
       :resource_type "File"
       :resource_title "tmp-directory"
       :property "ensure"
       :name nil
       :new_value "directory"
       :old_value "absent"
       :corrective_change false
       :message "created"
       :file "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
       :line 11
       :containment_path ["Foo" "" "Bar[Baz]"]
       :containing_class "Foo"}]}}

   :basic3
   {:certname "foo.local"
    :puppet_version "3.0.1"
    :report_format 4
    :transaction_uuid "e1e561ba-212f-11e3-9d58-60a44c233a9d"
    :configuration_version "a81jasj123"
    :start_time "2011-01-03T12:00:00-03:00"
    :corrective_change false
    :end_time "2011-01-03T12:10:00-03:00"
    :producer_timestamp "2011-01-03T12:11:00-03:00"
    :producer "bar.com"
    :catalog_uuid "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :code_id nil
    :job_id nil
    :cached_catalog_status "not_used"
    :environment "DEV"
    :status "unchanged"
    :noop false
    :noop_pending false
    :logs
    {:href ""
     :data
     [{:file nil,
       :line nil,
       :level "info",
       :message "Caching catalog for mbp.local",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.321565000-08:00"}
      {:file nil,
       :line nil,
       :level "info",
       :message "Applying configuration version '1424992544'",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.388965000-08:00"}]}
    :metrics
    {:href ""
     :data
     [{:category  "resources"
       :name  "changed"
       :value  3.14}
      {:category  "resources"
       :name  "failed"
       :value  2.71}
      {:category  "resources"
       :name  "failed_to_restart"
       :value  0}]}
    :resource_events
    {:href ""
     :data
     [{:certname "foo.local"
       :status "success"
       :timestamp "2011-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "notify, yo"
       :property "message"
       :name "foo"
       :new_value "notify, yo"
       :old_value ["what" "the" "woah"]
       :corrective_change false
       :message "defined 'message' as 'notify, yo'"
       :file "foo.pp"
       :line 1
       :containment_path nil
       :containing_class nil}
      {:certname "foo.local"
       :status "failure"
       :timestamp "2011-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "notify, yar"
       :property "message"
       :name "bar"
       :corrective_change false
       :new_value {"absent" 5}
       :old_value {"absent" true}
       :message "defined 'message' as 'notify, yo'"
       :file nil
       :line nil
       :containment_path []
       :containing_class nil}
      {:certname "foo.local"
       :status "skipped"
       :timestamp "2011-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "hi"
       :property nil
       :name nil
       :new_value nil
       :old_value nil
       :corrective_change false
       :message nil
       :file "bar"
       :line 2
       :containment_path ["Foo" "" "Bar[Baz]"]
       :containing_class "Foo"}]}}

   :basic4
   {:certname "foo.local"
    :puppet_version "3.0.1"
    :report_format 4
    :transaction_uuid "e1e561ba-212f-11e3-9d58-60a44c233a9e"
    :catalog_uuid "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :code_id nil
    :job_id nil
    :cached_catalog_status "not_used"
    :configuration_version "a81jasj123"
    :start_time "2011-01-03T12:00:00-03:00"
    :end_time "2011-01-03T12:10:00-03:00"
    :producer_timestamp "2011-01-03T12:11:00-03:00"
    :producer "bar.com"
    :corrective_change false
    :environment "DEV"
    :status "unchanged"
    :noop false
    :noop_pending true
    :logs
    {:href ""
     :data
     [{:file nil,
       :line nil,
       :level "info",
       :message "Caching catalog for mbp.local",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.321565000-08:00"}
      {:file nil,
       :line nil,
       :level "info",
       :message "Applying configuration version '1424992544'",
       :source "//mbp.local/Puppet",
       :tags [ "info" ],
       :time "2015-02-26T15:20:17.388965000-08:00"}]}
    :metrics
    {:href ""
     :data
     [{:category  "resources"
       :name  "changed"
       :value  3.14}
      {:category  "resources"
       :name  "failed"
       :value  2.71}
      {:category  "resources"
       :name  "failed_to_restart"
       :value  0}]}
    :resource_events
    {:href ""
     :data
     [{:certname "foo.local"
       :status "success"
       :timestamp "2011-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "notify, yo"
       :property "message"
       :name "foo"
       :new_value "notify, yo"
       :corrective_change false
       :old_value ["what" "the" "woah"]
       :message "defined 'message' as 'notify, yo'"
       :file "aaa.pp"
       :line 1
       :containment_path nil
       :containing_class nil}
      {:certname "foo.local"
       :status "success"
       :timestamp "2012-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "notify, yar"
       :property "message"
       :name "bar"
       :corrective_change false
       :new_value {"absent" 5}
       :old_value {"absent" true}
       :message "defined 'message' as 'notify, yo'"
       :file "bbb.pp"
       :line 2
       :containment_path []
       :containing_class nil}
      {:certname "foo.local"
       :status "skipped"
       :timestamp "2013-01-03T12:00:00-03:00"
       :resource_type "Notify"
       :resource_title "hi"
       :corrective_change false
       :property nil
       :name nil
       :new_value nil
       :old_value nil
       :message nil
       :file "ccc.pp"
       :line 3
       :containment_path ["Foo" "" "Bar[Baz]"]
       :containing_class "Foo"}]}}
   })

(def v8-report
  (s/validate reports/report-wireformat-schema
              (-> (:basic reports)
                  reports/report-query->wire-v8)))

(def v7-report
  (let [update-fn #(dissoc % :corrective_change)]
    (s/validate
      reports/report-v7-wireformat-schema
      (-> v8-report
          (dissoc :producer :noop_pending :corrective_change :job_id)
          (update :resources #(mapv (reports/update-resource-events update-fn) %))))))

(def v6-report
  (s/validate reports/report-v6-wireformat-schema
              (-> v7-report
                  (dissoc :catalog_uuid :cached_catalog_status :code_id))))

(def v5-report
  (s/validate reports/report-v5-wireformat-schema
              (-> (:basic reports)
                  reports/report-query->wire-v5)))

(def v4-report
  (s/validate reports/report-v4-wireformat-schema
              (-> v5-report
                  (dissoc :producer_timestamp :metrics :logs :noop)
                  utils/underscore->dash-keys
                  (update :resource-events #(map utils/underscore->dash-keys %)))))
