(ns com.puppetlabs.puppetdb.test.http.v3.events
  (:require [com.puppetlabs.puppetdb.reports :as report]
            [com.puppetlabs.utils :as utils]
            [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json])
  (:use clojure.test
        [clojure.walk :only [stringify-keys]]
        ring.mock.request
        com.puppetlabs.puppetdb.examples.reports
        com.puppetlabs.puppetdb.fixtures
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-string to-long]]
        [com.puppetlabs.puppetdb.testutils :only (response-equal? assert-success! get-request paged-results)]
        [com.puppetlabs.puppetdb.testutils.reports :only (store-example-report! get-events-map)]))

(def content-type-json pl-http/json-response-content-type)

(use-fixtures :each with-test-db with-http-app)

(defn get-response
  ([query]
    (get-response query {}))
  ([query extra-query-params]
    (*app* (get-request "/v3/events" query extra-query-params))))

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
  (map #(utils/maptrans {[:old-value :new-value] stringify-keys} %) events))

(defn expected-resource-event-response
  [resource-event report-hash configuration-version]
  (-> resource-event
    ;; the examples don't include the report hash or config version,
    ;; so we munge them into place
    (assoc-in [:report] report-hash)
    (assoc-in [:configuration-version] configuration-version)
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    (update-in [:timestamp] to-string)
    (dissoc :test-id)))

(defn expected-resource-events-response
  [resource-events report-hash configuration-version]
  (set (map #(expected-resource-event-response % report-hash configuration-version) resource-events)))

(deftest query-by-report
  (let [basic         (:basic reports)
        report-hash   (store-example-report! basic (now))
        conf-version  (:configuration-version basic)
        basic-events  (get-events-map basic)]

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report hash"
      (let [response (get-response ["=" "report" report-hash])
            expected (expected-resource-events-response (:resource-events basic) report-hash conf-version)]
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
                            (utils/select-values basic-events [1 3])
                            report-hash
                            conf-version)]
            (response-equal? response expected munge-event-values)))

        (testing "should support compound timestamp queries"
          (let [response (get-response ["and" [">" "timestamp" start-time]
                                              ["<" "timestamp" end-time]])
                expected (expected-resource-events-response
                            (utils/select-values basic-events [3])
                            report-hash
                            conf-version)]
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
                          (utils/select-values basic-events matches)
                          report-hash
                          conf-version)]
          (response-equal? response expected munge-event-values))))


    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through events " label " counts")
        (let [results (paged-results
                        {:app-fn  *app*
                         :path    "/v3/events"
                         :query   ["=" "report" report-hash]
                         :limit   1
                         :total   (count (:resource-events basic))
                         :include-total  count?
                         :params  {:order-by (json/generate-string [{"field" "status"}])}})]
          (is (= (count (:resource-events basic)) (count results)))
          (is (= (expected-resource-events-response
                   (:resource-events basic)
                   report-hash conf-version)
                (set (munge-event-values results)))))))

    (testing "order-by field names"
      (testing "should accept dashes"
        (let [expected  (expected-resource-events-response (:resource-events basic) report-hash conf-version)
              response  (get-response [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource-title"}])})]
          (is (= (:status response) pl-http/status-ok))
          (response-equal? response expected munge-event-values)))

      (testing "should reject underscores"
        (let [response  (get-response [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource_title"}])})
              body      (get response :body "null")]
          (is (= (:status response) pl-http/status-bad-request))
          (is (re-find #"Unrecognized column 'resource_title' specified in :order-by" body)))))))

(deftest query-distinct-resources
  (let [basic         (:basic reports)
        report-hash   (store-example-report! basic (now))
        conf-version  (:configuration-version basic)
        basic-events  (get-events-map basic)

        basic3        (:basic3 reports)
        report-hash3  (store-example-report! basic3 (now))
        conf-version3 (:configuration-version basic3)
        basic-events3 (get-events-map basic3)]
    (testing "should return only one event for a given resource"
      (let [expected  (expected-resource-events-response (:resource-events basic3) report-hash3 conf-version3)
            response  (get-response ["=", "certname", "foo.local"] {:distinct-resources true})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))
