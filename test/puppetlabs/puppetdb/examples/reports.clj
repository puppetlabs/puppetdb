(ns puppetlabs.puppetdb.examples.reports)

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet_version         "3.0.1"
    :report_format          4
    :transaction_uuid       "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :configuration_version  "a81jasj123"
    :start_time             "2011-01-01T12:00:00-03:00"
    :end_time               "2011-01-01T12:10:00-03:00"
    :environment            "DEV"
    :status                 "unchanged"
    :resource_events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output
    ;;  To make it easier to test output, we're included them here.  We also include
    ;;  a `:test_id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
    [{:test_id          1
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2011-01-01T12:00:01-03:00"
      :resource_type    "Notify"
      :resource_title   "notify, yo"
      :environment      "DEV"
      :property         "message"
      :new_value        "notify, yo"
      :old_value        ["what" "the" "woah"]
      :message          "defined 'message' as 'notify, yo'"
      :file             "foo.pp"
      :line             1
      :containment_path nil
      :containing_class nil}
     {:test_id          2
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2011-01-01T12:00:03-03:00"
      :resource_type    "Notify"
      :environment      "DEV"
      :resource_title   "notify, yar"
      :property         "message"
      :new_value        {"absent" 5}
      :old_value        {"absent" true}
      :message          "defined 'message' as 'notify, yo'"
      :file             nil
      :line             nil
      :containment_path []
      :containing_class nil}
     {:test_id          3
      :certname         "foo.local"
      :status           "skipped"
      :timestamp        "2011-01-01T12:00:02-03:00"
      :environment      "DEV"
      :resource_type    "Notify"
      :resource_title   "hi"
      :property         nil
      :new_value        nil
      :old_value        nil
      :message          nil
      :file             "bar"
      :line             2
      :containment_path ["Foo" "" "Bar[Baz]"]
      :containing_class "Foo"}]}

   :basic2
   {:certname               "foo.local"
    :puppet_version         "3.0.1"
    :report_format          4
    :transaction_uuid       "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :configuration_version  "bja3985a23"
    :start_time             "2013-08-28T19:00:00-03:00"
    :end_time               "2013-08-28T19:10:00-03:00"
    :environment            "DEV"
    :status                 "unchanged"
    :resource_events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname on input, but they will have one on output.  To make it
    ;;  easier to test output, we're included them here.  We also include a
    ;;  `:test_id` field to make it easier to reference individual events during
    ;;  testing.  Both of this are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
    [{:test_id          4
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2013-08-28T19:36:34.000Z"
      :resource_type    "Notify"
      :resource_title   "Creating tmp directory at /Users/foo/tmp"
      :property         "message"
      :new_value        "Creating tmp directory at /Users/foo/tmp"
      :old_value        "absent"
      :message          "defined 'message' as 'Creating tmp directory at /Users/foo/tmp'"
      :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
      :line             8
      :containment_path nil
      :containing_class nil}
     {:test_id          5
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2013-08-28T17:55:45.000Z"
      :resource_type    "File"
      :resource_title   "puppet-managed-file"
      :property         "ensure"
      :new_value        "present"
      :old_value        "absent"
      :message          "created"
      :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
      :line             17
      :containment_path []
      :containing_class nil}
     {:test_id          6
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2013-08-28T17:55:45.000Z"
      :resource_type    "File"
      :resource_title   "tmp-directory"
      :property         "ensure"
      :new_value        "directory"
      :old_value        "absent"
      :message          "created"
      :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
      :line             11
      :containment_path ["Foo" "" "Bar[Baz]"]
      :containing_class "Foo"}]}

   :basic3
   {:certname               "foo.local"
    :puppet_version         "3.0.1"
    :report_format          4
    :transaction_uuid       "e1e561ba-212f-11e3-9d58-60a44c233a9d"
    :configuration_version  "a81jasj123"
    :start_time             "2011-01-03T12:00:00-03:00"
    :end_time               "2011-01-03T12:10:00-03:00"
    :environment            "DEV"
    :status                 "unchanged"
    :resource_events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output
    ;;  To make it easier to test output, we're included them here.  We also include
    ;;  a `:test_id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
    [{:test_id          7
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2011-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "notify, yo"
      :property         "message"
      :new_value        "notify, yo"
      :old_value        ["what" "the" "woah"]
      :message          "defined 'message' as 'notify, yo'"
      :file             "foo.pp"
      :line             1
      :containment_path nil
      :containing_class nil}
     {:test_id          8
      :certname         "foo.local"
      :status           "failure"
      :timestamp        "2011-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "notify, yar"
      :property         "message"
      :new_value        {"absent" 5}
      :old_value        {"absent" true}
      :message          "defined 'message' as 'notify, yo'"
      :file             nil
      :line             nil
      :containment_path []
      :containing_class nil}
     {:test_id          9
      :certname         "foo.local"
      :status           "skipped"
      :timestamp        "2011-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "hi"
      :property         nil
      :new_value        nil
      :old_value        nil
      :message          nil
      :file             "bar"
      :line             2
      :containment_path ["Foo" "" "Bar[Baz]"]
      :containing_class "Foo"}]}

   :basic4
   {:certname               "foo.local"
    :puppet_version         "3.0.1"
    :report_format          4
    :transaction_uuid       "e1e561ba-212f-11e3-9d58-60a44c233a9d"
    :configuration_version  "a81jasj123"
    :start_time             "2011-01-03T12:00:00-03:00"
    :end_time               "2011-01-03T12:10:00-03:00"
    :environment            "DEV"
    :status                 "unchanged"
    :resource_events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output
    ;;  To make it easier to test output, we're included them here.  We also include
    ;;  a `:test_id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
    [{:test_id          10
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2011-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "notify, yo"
      :property         "message"
      :new_value        "notify, yo"
      :old_value        ["what" "the" "woah"]
      :message          "defined 'message' as 'notify, yo'"
      :file             "aaa.pp"
      :line             1
      :containment_path nil
      :containing_class nil}
     {:test_id          11
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2012-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "notify, yar"
      :property         "message"
      :new_value        {"absent" 5}
      :old_value        {"absent" true}
      :message          "defined 'message' as 'notify, yo'"
      :file             "bbb.pp"
      :line             2
      :containment_path []
      :containing_class nil}
     {:test_id          12
      :certname         "foo.local"
      :status           "skipped"
      :timestamp        "2013-01-03T12:00:00-03:00"
      :resource_type    "Notify"
      :resource_title   "hi"
      :property         nil
      :new_value        nil
      :old_value        nil
      :message          nil
      :file             "ccc.pp"
      :line             3
      :containment_path ["Foo" "" "Bar[Baz]"]
      :containing_class "Foo"}]}
   })
