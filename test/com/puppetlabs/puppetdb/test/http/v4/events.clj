(ns com.puppetlabs.puppetdb.test.http.v4.events
  (:require [com.puppetlabs.puppetdb.reports :as report]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json])
  (:use clojure.test
        [clojure.walk :only [stringify-keys]]
        ring.mock.request
        com.puppetlabs.puppetdb.examples.reports
        com.puppetlabs.puppetdb.fixtures
        [clj-time.core :only [ago now secs]]
        [clj-time.coerce :only [to-string to-long to-timestamp]]
        [com.puppetlabs.puppetdb.testutils :only (response-equal? assert-success! get-request paged-results)]
        [com.puppetlabs.puppetdb.testutils.reports :only (store-example-report! get-events-map)]))

(def endpoint "/v4/events")

(def content-type-json pl-http/json-response-content-type)

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([query]
    (get-response query {}))
  ([query extra-query-params]
    (*app* (get-request endpoint query extra-query-params))))

(defn munge-event-values
  "Munge the event values that we get back from the web to a format suitable
  for comparison with test data.  This generally involves things like converting
  map keys from keywords to strings, etc."
  [events]
  ;; It is possible for the `old-value` and `new-value` field of an event
  ;; to contain values that are complex data types (arrays, maps).  In
  ;; the case where one of these values is a map, we will get it back
  ;; with keywords as keys, but real world-data has strings as keys.  Here
  ;; we simply convert the keys to strings so that we can compare them for
  ;; tests.
  (map #(kitchensink/maptrans {[:old-value :new-value] stringify-keys} %) events))

(defn expected-resource-event-response
  [resource-event report]
  (-> resource-event
    ;; the examples don't include the report hash or config version,
    ;; so we munge them into place
    (assoc-in [:report] (:hash report))
    (assoc-in [:configuration-version] (:configuration-version report))
    (assoc-in [:run-start-time] (to-string (:start-time report)))
    (assoc-in [:run-end-time] (to-string (:end-time report)))
    (assoc-in [:report-receive-time] (to-string (:receive-time report)))
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    (update-in [:timestamp] to-string)
    (dissoc :test-id)))

(defn expected-resource-events-response
  [resource-events report]
  (set (map #(expected-resource-event-response % report) resource-events)))

(deftest query-by-report
  (let [basic             (store-example-report! (:basic reports) (now))
        basic-events      (get-in reports [:basic :resource-events])
        basic-events-map  (get-events-map (:basic reports))
        report-hash       (:hash basic)]

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report hash"
      (let [response (get-response ["=" "report" report-hash])
            expected (expected-resource-events-response basic-events basic)]
        (response-equal? response expected munge-event-values)))

    (testing "query exceeding event-query-limit"
      (with-http-app {:event-query-limit 1}
        (fn []
          (let [response (get-response ["=" "report" report-hash])
                body     (get response :body "null")]
            (is (= (:status response) pl-http/status-internal-error))
            (is (re-find #"more than the maximum number of results" body))))))

    ;; NOTE: more exhaustive testing for these queries can be found in
    ;; `com.puppetlabs.puppetdb.test.query.event`
    (testing "should support querying resource events by timestamp"
      (let [start-time  "2011-01-01T12:00:01-03:00"
            end-time    "2011-01-01T12:00:03-03:00"]

        (testing "should support single term timestamp queries"
          (let [response (get-response ["<" "timestamp" end-time])
                expected (expected-resource-events-response
                            (kitchensink/select-values basic-events-map [1 3])
                            basic)]
            (response-equal? response expected munge-event-values)))

        (testing "should support compound timestamp queries"
          (let [response (get-response ["and" [">" "timestamp" start-time]
                                              ["<" "timestamp" end-time]])
                expected (expected-resource-events-response
                            (kitchensink/select-values basic-events-map [3])
                            basic)]
            (response-equal? response expected munge-event-values)))))

    (testing "compound queries"
      (doseq [[query matches]
              [[["and"
                  ["or"
                    ["=" "resource-title" "hi"]
                    ["=" "resource-title" "notify, yo"]]
                  ["=" "status" "success"]]                       [1]]
               [["or"
                  ["and"
                    ["=" "resource-title" "hi"]
                    ["=" "status" "success"]]
                  ["and"
                    ["=" "resource-type" "Notify"]
                    ["=" "property" "message"]]]                  [1 2]]
               [["and"
                  ["=" "status" "success"]
                  ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1]]
               [["or"
                  ["=" "status" "skipped"]
                  ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1 3]]]]
        (let [response  (get-response query)
              expected  (expected-resource-events-response
                          (kitchensink/select-values basic-events-map matches)
                          basic)]
          (response-equal? response expected munge-event-values))))


    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through events " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    endpoint
                         :query   ["=" "report" report-hash]
                         :limit   1
                         :total   (count basic-events)
                         :include-total  count?
                         :params  {:order-by (json/generate-string [{"field" "status"}])}})]
          (is (= (count basic-events) (count results)))
          (is (= (expected-resource-events-response
                   basic-events
                   basic)
                (set (munge-event-values results)))))))

    (testing "order-by field names"
      (testing "should accept dashes"
        (let [expected  (expected-resource-events-response basic-events basic)
              response  (get-response [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource-title"}])})]
          (is (= (:status response) pl-http/status-ok))
          (response-equal? response expected munge-event-values)))

      (testing "should reject underscores"
        (let [response  (get-response [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource_title"}])})
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Unrecognized column 'resource_title' specified in :order-by" body)))))))

(deftest query-distinct-resources
  (let [basic             (store-example-report! (:basic reports) (now))
        basic-events      (get-in reports [:basic :resource-events])
        basic-events-map  (get-events-map (:basic reports))

        basic3            (store-example-report! (:basic3 reports) (now))
        basic3-events     (get-in reports [:basic3 :resource-events])
        basic3-events-map (get-events-map (:basic3 reports))]

    (testing "should return an error if the caller passes :distinct-resources without timestamps"
      (let [response  (get-response ["=" "certname" "foo.local"] {:distinct-resources true})
            body      (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find
              #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
              body)))
      (let [response  (get-response ["=" "certname" "foo.local"] {:distinct-resources true
                                                                  :distinct-start-time 0})
            body      (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find
              #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
              body)))
      (let [response  (get-response ["=" "certname" "foo.local"] {:distinct-resources true
                                                                  :distinct-end-time 0})
            body      (get response :body "null")]
        (is (= (:status response) pl-http/status-bad-request))
        (is (re-find
              #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
              body))))

    (testing "should return only one event for a given resource"
      (let [expected  (expected-resource-events-response basic3-events basic3)
            response  (get-response ["=", "certname", "foo.local"] {:distinct-resources true
                                                                    :distinct-start-time 0
                                                                    :distinct-end-time (now)})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "events should be contained within distinct resource timestamps"
      (let [expected  (expected-resource-events-response basic-events basic)
            response  (get-response ["=", "certname", "foo.local"]
                                    {:distinct-resources true
                                     :distinct-start-time 0
                                     :distinct-end-time "2011-01-02T12:00:01-03:00"})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "filters (such as status) should be applied *after* the distinct list of most recent events has been built up"
      (let [expected  #{}
            response (get-response ["and" ["=" "certname" "foo.local"]
                                          ["=" "status" "success"]
                                          ["=" "resource-title" "notify, yar"]]
                                   {:distinct-resources true
                                    :distinct-start-time 0
                                    :distinct-end-time (now)})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))

(deftest query-by-puppet-report-timestamp
  (let [basic         (store-example-report! (:basic reports) (now))
        basic-events  (get-in reports [:basic :resource-events])

        basic3        (store-example-report! (:basic3 reports) (now))
        basic3-events (get-in reports [:basic3 :resource-events])]

    (testing "query by report start time"
      (let [expected  (expected-resource-events-response basic-events basic)
            response  (get-response ["<", "run-start-time" "2011-01-02T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by report end time"
      (let [expected  (expected-resource-events-response basic3-events basic3)
            response  (get-response [">", "run-end-time" "2011-01-02T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by end time w/no results"
      (let [expected  #{}
            response  (get-response [">", "run-end-time" "2011-01-04T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))

(deftest query-by-report-receive-timestamp
  (let [test-start-time (ago (secs 1))

        basic           (store-example-report! (:basic reports) (now))
        basic-events    (get-in reports [:basic :resource-events])]
    (testing "query by report receive time"
      (let [expected  (expected-resource-events-response basic-events basic)
            response  (get-response [">", "report-receive-time" (to-string test-start-time)])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by receive time w/no results"
      (let [expected  #{}
            response  (get-response ["<", "report-receive-time" (to-string test-start-time)])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))
