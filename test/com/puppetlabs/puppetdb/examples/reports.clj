(ns com.puppetlabs.puppetdb.examples.reports
  (:require [clj-time.coerce :as coerce]))

(def reports
  {:basic
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :transaction-uuid       "68b08e2a-eeb1-4322-b241-bfdf151d294b"
    :configuration-version  "a81jasj123"
    :start-time             "2011-01-01T12:00:00-03:00"
    :end-time               "2011-01-01T12:10:00-03:00"
    :resource-events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output
    ;;  To make it easier to test output, we're included them here.  We also include
    ;;  a `:test-id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
        [{:test-id          1
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2011-01-01T12:00:01-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yo"
          :property         "message"
          :new-value        "notify, yo"
          :old-value        ["what" "the" "woah"]
          :message          "defined 'message' as 'notify, yo'"
          :file             "foo.pp"
          :line             1
          :containment-path nil
          :containing-class nil}
         {:test-id          2
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2011-01-01T12:00:03-03:00"
          :resource-type    "Notify"
          :resource-title   "notify, yar"
          :property         "message"
          :new-value        {"absent" 5}
          :old-value        {"absent" true}
          :message          "defined 'message' as 'notify, yo'"
          :file             nil
          :line             nil
          :containment-path []
          :containing-class nil}
         {:test-id          3
          :certname         "foo.local"
          :status           "skipped"
          :timestamp        "2011-01-01T12:00:02-03:00"
          :resource-type    "Notify"
          :resource-title   "hi"
          :property         nil
          :new-value        nil
          :old-value        nil
          :message          nil
          :file             "bar"
          :line             2
          :containment-path ["Foo" "" "Bar[Baz]"]
          :containing-class "Foo"}]}

   :basic2
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :transaction-uuid       "5ea3a70b-84c8-426c-813c-dd6492fb829b"
    :configuration-version  "bja3985a23"
    :start-time             "2013-08-28T19:00:00-03:00"
    :end-time               "2013-08-28T19:10:00-03:00"
    :resource-events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname on input, but they will have one on output.  To make it
    ;;  easier to test output, we're included them here.  We also include a
    ;;  `:test-id` field to make it easier to reference individual events during
    ;;  testing.  Both of this are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
        [{:test-id          4
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2013-08-28T19:36:34.000Z"
          :resource-type    "Notify"
          :resource-title   "Creating tmp directory at /Users/foo/tmp"
          :property         "message"
          :new-value        "Creating tmp directory at /Users/foo/tmp"
          :old-value        "absent"
          :message          "defined 'message' as 'Creating tmp directory at /Users/foo/tmp'"
          :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
          :line             8
          :containment-path nil
          :containing-class nil}
         {:test-id          5
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2013-08-28T17:55:45.000Z"
          :resource-type    "File"
          :resource-title   "puppet-managed-file"
          :property         "ensure"
          :new-value        "present"
          :old-value        "absent"
          :message          "created"
          :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
          :line             17
          :containment-path []
          :containing-class nil}
         {:test-id          6
          :certname         "foo.local"
          :status           "success"
          :timestamp        "2013-08-28T17:55:45.000Z"
          :resource-type    "File"
          :resource-title   "tmp-directory"
          :property         "ensure"
          :new-value        "directory"
          :old-value        "absent"
          :message          "created"
          :file             "/Users/foo/workspace/puppetlabs/conf/puppet/master/conf/manifests/site.pp"
          :line             11
          :containment-path ["Foo" "" "Bar[Baz]"]
          :containing-class "Foo"}]}

   :basic3
   {:certname               "foo.local"
    :puppet-version         "3.0.1"
    :report-format          3
    :transaction-uuid       "e1e561ba-212f-11e3-9d58-60a44c233a9d"
    :configuration-version  "a81jasj123"
    :start-time             "2011-01-02T12:00:00-03:00"
    :end-time               "2011-01-02T12:10:00-03:00"
    :resource-events
    ;; NOTE: this is a bit wonky because resource events should *not* contain
    ;;  a certname or containment-class on input, but they will have one on output
    ;;  To make it easier to test output, we're included them here.  We also include
    ;;  a `:test-id` field to make it easier to reference individual events during
    ;;  testing.  All of these are munged out by the testutils `store-example-report!`
    ;;  function before the report is submitted to the test database.
    [{:test-id          7
      :certname         "foo.local"
      :status           "success"
      :timestamp        "2011-01-02T12:00:00-03:00"
      :resource-type    "Notify"
      :resource-title   "notify, yo"
      :property         "message"
      :new-value        "notify, yo"
      :old-value        ["what" "the" "woah"]
      :message          "defined 'message' as 'notify, yo'"
      :file             "foo.pp"
      :line             1
      :containment-path nil
      :containing-class nil}
     {:test-id          8
      :certname         "foo.local"
      :status           "failure"
      :timestamp        "2011-01-02T12:00:00-03:00"
      :resource-type    "Notify"
      :resource-title   "notify, yar"
      :property         "message"
      :new-value        {"absent" 5}
      :old-value        {"absent" true}
      :message          "defined 'message' as 'notify, yo'"
      :file             nil
      :line             nil
      :containment-path []
      :containing-class nil}
     {:test-id          9
      :certname         "foo.local"
      :status           "skipped"
      :timestamp        "2011-01-02T12:00:00-03:00"
      :resource-type    "Notify"
      :resource-title   "hi"
      :property         nil
      :new-value        nil
      :old-value        nil
      :message          nil
      :file             "bar"
      :line             2
      :containment-path ["Foo" "" "Bar[Baz]"]
      :containing-class "Foo"}]}

   })
