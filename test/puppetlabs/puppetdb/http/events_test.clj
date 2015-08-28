(ns puppetlabs.puppetdb.http.events-test
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.testutils.events :refer [http-expected-resource-events]]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [clj-time.core :refer [ago now seconds]]
            [clojure.set :as clj-set]
            [clj-time.coerce :refer [to-string to-long to-timestamp]]
            [puppetlabs.puppetdb.testutils :refer [response-equal?
                                                   assert-success!
                                                   get-request
                                                   paged-results
                                                   deftestseq]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report! enumerated-resource-events-map]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response
                                                        order-param
                                                        query-result]]
            [clojure.walk :refer [stringify-keys]]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]))

(def endpoints [[:v4 "/v4/events"]
                [:v4 "/v4/environments/DEV/events"]])

(def content-type-json http/json-response-content-type)

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([endpoint query]
   (get-response endpoint query {}))
  ([endpoint query extra-query-params]
   (let [resp (*app* (get-request endpoint query extra-query-params))]
     (update-in resp
                [:body]
                (fn [body]
                  (if (string? body)
                    body
                    (slurp body)))))))

(defn parse-result
  "Stringify (if needed) then parse the response"
  [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Throwable e
      body)))

(defn strip-count-fields
  [responses]
  (map #(dissoc % :count) responses))

(defn is-query-result
  [endpoint query expected-results]
  (let [request (get-request endpoint (json/generate-string query))
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count expected-results) (count actual-result)))
    (is (= expected-results (set actual-result)))
    (is (= http/status-ok status))))

(defn munge-event-values
  "Munge the event values that we get back from the web to a format suitable
  for comparison with test data.  This generally involves things like converting
  map keys from keywords to strings, etc."
  [events]
  ;; It is possible for the `old_value` and `new_value` field of an event
  ;; to contain values that are complex data types (arrays, maps).  In
  ;; the case where one of these values is a map, we will get it back
  ;; with keywords as keys, but real world-data has strings as keys.  Here
  ;; we simply convert the keys to strings so that we can compare them for
  ;; tests.
  (map #(kitchensink/maptrans {[:old_value :new_value] stringify-keys} %) events))

(deftestseq query-by-report
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic-report (:basic reports)
        basic (store-example-report! basic-report (now))
        basic-events (:resource_events basic-report)
        basic-events-map (enumerated-resource-events-map basic-events)
        report-hash (:hash basic)]

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report hash"
      (let [response (query-response endpoint ["=" "report" report-hash])
            expected (http-expected-resource-events version basic-events basic)]
        (response-equal? response expected munge-event-values)))

    ;; NOTE: more exhaustive testing for these queries can be found in
    ;; `puppetlabs.puppetdb.query.event-test`
    (testing "should support querying resource events by timestamp"
      (let [start_time "2011-01-01T12:00:01-03:00"
            end_time   "2011-01-01T12:00:03-03:00"]

        (testing "should support single term timestamp queries"
          (let [response (query-result method endpoint ["<" "timestamp" end_time]
                                       {} munge-event-values)
                expected (http-expected-resource-events
                          version
                          (kitchensink/select-values basic-events-map [0 2])
                          basic)]
            (is (= response expected))))

        (testing "should support compound timestamp queries"
          (let [response (get-response endpoint ["and" [">" "timestamp" start_time]
                                                 ["<" "timestamp" end_time]]
                                       {} munge-event-values)
                expected (http-expected-resource-events
                          version
                          (kitchensink/select-values basic-events-map [2])
                          basic)]
            (is (= response expected))))))

    (testing "compound queries"
      (doseq [[query matches]
              [[["and"
                 ["or"
                  ["=" "resource_title" "hi"]
                  ["=" "resource_title" "notify, yo"]]
                 ["=" "status" "success"]]                       [0]]
               [["or"
                 ["and"
                  ["=" "resource_title" "hi"]
                  ["=" "status" "success"]]
                 ["and"
                  ["=" "resource_type" "Notify"]
                  ["=" "property" "message"]]]                   [0 1]]
               [["and"
                 ["=" "status" "success"]
                 ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [0]]
               [["or"
                 ["=" "status" "skipped"]
                 ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [0 2]]]]
        (let [response  (query-result method endpoint query {} munge-event-values)
              expected  (http-expected-resource-events
                         version
                         (kitchensink/select-values basic-events-map matches)
                         basic)]
          (is (= response expected)))))

    (testing "compound queries with a projection"
      (doseq [[query matches ks]
              [[["extract" "status"
                 ["and"
                  ["or"
                   ["=" "resource_title" "hi"]
                   ["=" "resource_title" "notify, yo"]]
                  ["=" "status" "success"]]]
                [0]
                [:status]]

               [["extract" ["status" "line"]
                 ["and"
                  ["or"
                   ["=" "resource_title" "hi"]
                   ["=" "resource_title" "notify, yo"]]
                  ["=" "status" "success"]]]
                [0]
                [:status :line]]

               [["extract" ["status" ["function" "count"]]
                 ["or"
                  ["=" "resource_title" "hi"]
                  ["=" "resource_title" "notify,yo"]]
                 ["group_by" "status"]]
               [2]
               [:status]]]]

        (let [response (query-result method endpoint query {} strip-count-fields)
              expected (->> (kitchensink/select-values basic-events-map matches)
                            (map #(select-keys % ks))
                            set)]
          (is (= response expected)))))


    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through events " label " counts")
        (let [results (paged-results
                       {:app-fn  *app*
                        :path    endpoint
                        :query   ["=" "report" report-hash]
                        :limit   1
                        :total   (count basic-events)
                        :include_total  count?
                        :params  {:order_by (json/generate-string [{"field" "status"}])}})]
          (is (= (count basic-events) (count results)))
          (is (= (http-expected-resource-events
                  version
                  basic-events
                  basic)
                 (set (munge-event-values results)))))))

    (testing "order_by field names"
      (testing "should accept underscores"
        (let [expected  (http-expected-resource-events version basic-events basic)
              response  (query-result method endpoint [">" "timestamp" 0]
                                      {:order_by (json/generate-string
                                                   [{:field "resource_title"}])}
                                      munge-event-values)]
          (is (= (:status response) http/status-ok))
          (is (= response expected))))

      (testing "should reject dashes"
        (let [response  (query-response method endpoint [">" "timestamp" 0]
                                        {:order_by (json/generate-string
                                                     [{:field "resource-title"}])})
              body      (get response :body "null")]
          (is (= (:status response) http/status-bad-request))
          (is (re-find #"Unrecognized column 'resource-title' specified in :order_by" body)))))))

(deftestseq query-distinct-resources
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic             (store-example-report! (:basic reports) (now))
        basic-events      (get-in reports [:basic :resource_events])

        basic3            (store-example-report! (:basic3 reports) (now))
        basic3-events     (get-in reports [:basic3 :resource_events])]

    (testing "should return an error if the caller passes :distinct_resources without timestamps"
      (let [response  (query-response method endpoint ["=" "certname" "foo.local"]
                                      {:distinct_resources true})
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'"
             body)))
      (let [response (query-response method endpoint ["=" "certname" "foo.local"]
                                     {:distinct_resources true :distinct_start_time 0})
            body (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'"
             body)))
      (let [response (query-response method endpoint ["=" "certname" "foo.local"]
                                     {:distinct_resources true :distinct_end_time 0})
            body (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'"
             body)))

      (let [response  (query-response method endpoint ["=" "certname" "foo.local"]
                                      {:distinct_start_time 0 :distinct_end_time 0})
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct_resources' query parameter must accompany parameters 'distinct_start_time' and 'distinct_end_time'"
             body))))

    (testing "should return only one event for a given resource"
      (let [expected  (http-expected-resource-events version basic3-events basic3)
            response  (query-result method endpoint ["=" "certname" "foo.local"]
                                    {:distinct_resources true
                                     :distinct_start_time 0
                                     :distinct_end_time (now)}
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "distinct params should work with include_total"
      (let [expected  (http-expected-resource-events version basic3-events basic3)
            response  (query-result method endpoint ["=" "certname" "foo.local"]
                                    {:distinct_resources true
                                     :distinct_start_time 0
                                     :include_total true
                                     :distinct_end_time (now)}
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "events should be contained within distinct resource timestamps"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (query-result method endpoint ["=" "certname" "foo.local"]
                                    {:distinct_resources true
                                     :distinct_start_time 0
                                     :distinct_end_time "2011-01-02T12:00:01-03:00"}
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "filters (such as status) should be applied *after* the distinct list of most recent events has been built up"
      (let [expected  #{}
            response (query-result method endpoint ["and" ["=" "certname" "foo.local"]
                                                    ["=" "status" "success"]
                                                    ["=" "resource_title" "notify, yar"]]
                                   {:distinct_resources true
                                    :distinct_start_time 0
                                    :distinct_end_time (now)}
                                   munge-event-values)]
        (assert-success! response)
        (is (= response expected))))))

(deftestseq query-by-puppet-report-timestamp
  [[version endpoint] endpoints
   method [:get :post]]

  (let [basic (store-example-report! (:basic reports) (now))
        basic-events (get-in reports [:basic :resource_events])

        basic3 (store-example-report! (:basic3 reports) (now))
        basic3-events (get-in reports [:basic3 :resource_events])]

    (testing "query by report start time"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (query-result method endpoint
                                    ["<" "run_start_time" "2011-01-02T00:00:00-03:00"]
                                    {} munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "query by report end time"
      (let [expected  (http-expected-resource-events version basic3-events basic3)
            response  (query-result method endpoint
                                    [">" "run_end_time" "2011-01-02T00:00:00-03:00"]
                                    {} munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "query without a query parameter"
      (let [expected  (clj-set/union (http-expected-resource-events version basic3-events basic3)
                                     (http-expected-resource-events version basic-events basic))
            response  (query-result method endpoint nil {} munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "query by end time w/no results"
      (let [expected  #{}
            response  (query-result method endpoint
                                    [">" "run_end_time" "2011-01-04T00:00:00-03:00"]
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))))

(deftestseq query-by-report-receive-timestamp
  [[version endpoint] endpoints
   method [:get :post]]

  (let [test-start-time (-> 1 seconds ago)
        basic           (store-example-report! (:basic reports) (now))
        basic-events    (get-in reports [:basic :resource_events])]
    (testing "query by report receive time"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (query-result method endpoint
                                    [">" "report_receive_time" (to-string test-start-time)]
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))

    (testing "query by receive time w/no results"
      (let [expected  #{}
            response  (query-result method endpoint
                                    ["<" "report_receive_time" (to-string test-start-time)]
                                    munge-event-values)]
        (assert-success! response)
        (is (= response expected))))))

(def versioned-subqueries
  (omap/ordered-map
   "/v4/events"
   (omap/ordered-map
    ["and"
     ["=" "containing_class" "Foo"]
     ["in" "certname" ["extract" "certname" ["select_resources"
                                             ["=" "title" "foobar"]]]]]

    #{{:containment_path ["Foo" "" "Bar[Baz]"]
       :new_value nil
       :containing_class "Foo"
       :report_receive_time "2014-04-16T12:44:40.978Z"
       :report "7d0cfa08901e1e1d80cf2f2f814d356d0e457e09"
       :resource_title "hi"
       :property nil
       :file "bar"
       :old_value nil
       :run_start_time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run_end_time "2011-01-01T15:10:00.000Z"
       :resource_type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration_version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}}

    ["and"
     ["=" "containing_class" "Foo"]
     ["in" "certname" ["extract" "certname" ["select_facts"
                                             ["=" "value" "1.1.1.1"]]]]]

    #{{:containment_path ["Foo" "" "Bar[Baz]"]
       :new_value nil
       :containing_class "Foo"
       :report_receive_time "2014-04-16T12:44:40.978Z"
       :report "7d0cfa08901e1e1d80cf2f2f814d356d0e457e09"
       :resource_title "hi"
       :property nil
       :file "bar"
       :old_value nil
       :run_start_time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run_end_time "2011-01-01T15:10:00.000Z"
       :resource_type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration_version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}}

    ;; test vector-valued field
    ["and"
     ["=" "containing_class" "Foo"]
     ["in" ["certname" "environment"]
      ["extract" ["certname" "environment"]
       ["select_resources" ["=" "title" "foobar"]]]]]

    #{{:containment_path ["Foo" "" "Bar[Baz]"]
       :new_value nil
       :containing_class "Foo"
       :report_receive_time "2014-04-16T12:44:40.978Z"
       :report "7d0cfa08901e1e1d80cf2f2f814d356d0e457e09"
       :resource_title "hi"
       :property nil
       :file "bar"
       :old_value nil
       :run_start_time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run_end_time "2011-01-01T15:10:00.000Z"
       :resource_type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration_version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}})))

(deftestseq valid-subqueries
  [[version endpoint] endpoints
   method [:get :post]]

  (let [catalog (:basic catalogs)
        certname (str (:certname catalog))
        report (assoc (:basic reports) :certname certname)
        timestamp "2014-04-16T12:44:40.978Z"]
    (scf-store/add-certname! certname)
    (store-example-report! report timestamp)
    (scf-store/replace-catalog! catalog (now))
    (scf-store/add-facts! {:certname certname
                           :values {"ipaddress" "1.1.1.1"}
                           :timestamp (now)
                           :environment nil
                           :producer_timestamp (now)}))

  (doseq [[query results] (get versioned-subqueries endpoint)]
    (testing (str "query: " query " should match expected output")
      (is-query-result endpoint query results))))

(def versioned-invalid-subqueries
  (omap/ordered-map
   "/v4/events" (omap/ordered-map
                 ;; Extract using invalid fields should throw an error
                 ["in" "certname" ["extract" "nothing" ["select_resources"
                                                        ["=" "type" "Class"]]]]
                 #"Can't extract unknown 'resources' field 'nothing'.*Acceptable fields are.*"

                 ["in" "certname" ["extract" ["nothing" "nothing2" "certname"] ["select_resources"
                                                                                ["=" "type" "Class"]]]]
                 #"Can't extract unknown 'resources' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"

                 ;; In-query for invalid fields should throw an error
                 ["in" "nothing" ["extract" "certname" ["select_resources"
                                                        ["=" "type" "Class"]]]]
                 #"Can't match on unknown 'events' field 'nothing' for 'in'.*Acceptable fields are.*"

                 ["in" ["certname" "nothing" "nothing2"] ["extract" "certname" ["select_resources"
                                                                                ["=" "type" "Class"]]]]
                 #"Can't match on unknown 'events' fields: 'nothing', 'nothing2' for 'in'.*Acceptable fields are.*")))

(deftestseq invalid-subqueries
  [[version endpoint] endpoints
   method  [:get :post]]

  (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (query-response
                                               method endpoint (json/generate-string query))]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

(def versioned-invalid-queries
  (omap/ordered-map
    "/v4/events" (omap/ordered-map

                   ;; string values invalid on numeric fields
                   ["=" "line" "100"]
                   #"Argument \"100\" is incompatible with numeric field \"line\"."
                   ;; Top level extract using invalid fields should throw an error
                   ["extract" "nothing" ["~" "certname" ".*"]]
                   #"Can't extract unknown 'events' field 'nothing'.*Acceptable fields are.*"

                   ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
                   #"Can't extract unknown 'events' fields: 'nothing', 'nothing2'.*Acceptable fields are.*")))

(deftestseq invalid-queries
  [[version endpoint] endpoints
   method  [:get :post]]

  (doseq [[query msg] (get versioned-invalid-queries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
   "/v4/events" (omap/ordered-map
                 ["~" "certname" "*abc"]
                 #".*invalid regular expression: quantifier operand invalid"

                 ["~" "certname" "[]"]
                 #".*invalid regular expression: brackets.*not balanced")))

(deftestseq ^{:hsqldb false} pg-invalid-regexps
  [[version endpoint] endpoints
   method  [:get :post]]

  (doseq [[query msg] (get pg-versioned-invalid-regexps endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (query-response method endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))
