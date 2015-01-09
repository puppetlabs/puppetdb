(ns puppetlabs.puppetdb.http.events-test
  (:require [puppetlabs.puppetdb.reports :as report]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json]
            [puppetlabs.puppetdb.testutils.events :refer [http-expected-resource-events]]
            [flatland.ordered.map :as omap]
            [puppetlabs.puppetdb.examples :refer [catalogs]]
            [clj-time.core :refer [ago now secs]]
            [clj-time.coerce :refer [to-string to-long to-timestamp]]
            [puppetlabs.puppetdb.testutils :refer [response-equal?
                                                   assert-success!
                                                   get-request
                                                   paged-results
                                                   deftestseq]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report! get-events-map]]
            [clojure.walk :refer [stringify-keys]]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
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
       (if (string? (:body resp))
         resp
         (update-in resp [:body] slurp)))))

(defn parse-result
  "Stringify (if needed) then parse the response"
  [body]
  (try
    (if (string? body)
      (json/parse-string body true)
      (json/parse-string (slurp body) true))
    (catch Throwable e
      body)))

(defn is-query-result
  [endpoint query expected-results]
  (let [request (get-request endpoint (json/generate-string query))
        {:keys [status body]} (*app* request)
        actual-result (parse-result body)]
    (is (= (count actual-result) (count expected-results)))
    (is (= (set actual-result) expected-results))
    (is (= status http/status-ok))))

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

(deftestseq query-by-report
  [[version endpoint] endpoints]

  (let [basic             (store-example-report! (:basic reports) (now))
        basic-events      (get-in reports [:basic :resource-events])
        basic-events-map  (get-events-map (:basic reports))
        report-hash       (:hash basic)]

    ;; TODO: test invalid requests

    (testing "should return the list of resource events for a given report hash"
      (let [response (get-response endpoint ["=" "report" report-hash])
            expected (http-expected-resource-events version basic-events basic)]
        (response-equal? response expected munge-event-values)))

    ;; NOTE: more exhaustive testing for these queries can be found in
    ;; `puppetlabs.puppetdb.query.event-test`
    (testing "should support querying resource events by timestamp"
      (let [start-time "2011-01-01T12:00:01-03:00"
            end-time   "2011-01-01T12:00:03-03:00"]

        (testing "should support single term timestamp queries"
          (let [response (get-response endpoint ["<" "timestamp" end-time])
                expected (http-expected-resource-events
                          version
                          (kitchensink/select-values basic-events-map [1 3])
                          basic)]
            (response-equal? response expected munge-event-values)))

        (testing "should support compound timestamp queries"
          (let [response (get-response endpoint ["and" [">" "timestamp" start-time]
                                                 ["<" "timestamp" end-time]])
                expected (http-expected-resource-events
                          version
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
                  ["=" "property" "message"]]]                   [1 2]]
               [["and"
                 ["=" "status" "success"]
                 ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1]]
               [["or"
                 ["=" "status" "skipped"]
                 ["<" "timestamp" "2011-01-01T12:00:02-03:00"]]  [1 3]]]]
        (let [response  (get-response endpoint query)
              expected  (http-expected-resource-events
                         version
                         (kitchensink/select-values basic-events-map matches)
                         basic)]
          (response-equal? response expected munge-event-values))))

    (testing "compound queries with a projection"
      (doseq [[query matches ks]
              [[["extract" "status"
                 ["and"
                  ["or"
                   ["=" "resource-title" "hi"]
                   ["=" "resource-title" "notify, yo"]]
                  ["=" "status" "success"]]]
                [1]
                [:status]]
               [["extract" ["status" "line"]
                 ["and"
                  ["or"
                   ["=" "resource-title" "hi"]
                   ["=" "resource-title" "notify, yo"]]
                  ["=" "status" "success"]]]
                [1]
                [:status :line]]]]
        (let [response (get-response endpoint query)
              expected (->> (kitchensink/select-values basic-events-map matches)
                         (map #(select-keys % ks))
                         set)]
          (response-equal? response expected))))


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
          (is (= (http-expected-resource-events
                  version
                  basic-events
                  basic)
                 (set (munge-event-values results)))))))

    (testing "order-by field names"
      (testing "should accept dashes"
        (let [expected  (http-expected-resource-events version basic-events basic)
              response  (get-response endpoint [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource-title"}])})]
          (is (= (:status response) http/status-ok))
          (response-equal? response expected munge-event-values)))

      (testing "should reject underscores"
        (let [response  (get-response endpoint [">", "timestamp", 0] {:order-by (json/generate-string [{:field "resource_title"}])})
              body      (get response :body "null")]
          (is (= (:status response) http/status-bad-request))
          (is (re-find #"Unrecognized column 'resource_title' specified in :order-by" body)))))))

(deftestseq query-distinct-resources
  [[version endpoint] endpoints]

  (let [basic             (store-example-report! (:basic reports) (now))
        basic-events      (get-in reports [:basic :resource-events])
        basic-events-map  (get-events-map (:basic reports))

        basic3            (store-example-report! (:basic3 reports) (now))
        basic3-events     (get-in reports [:basic3 :resource-events])
        basic3-events-map (get-events-map (:basic3 reports))]

    (testing "should return an error if the caller passes :distinct-resources without timestamps"
      (let [response  (get-response endpoint ["=" "certname" "foo.local"] {:distinct-resources true})
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
             body)))
      (let [response  (get-response endpoint ["=" "certname" "foo.local"] {:distinct-resources true
                                                                           :distinct-start-time 0})
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
             body)))
      (let [response  (get-response endpoint ["=" "certname" "foo.local"] {:distinct-resources true
                                                                           :distinct-end-time 0})
            body      (get response :body "null")]
        (is (= (:status response) http/status-bad-request))
        (is (re-find
             #"'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'"
             body))))

    (testing "should return only one event for a given resource"
      (let [expected  (http-expected-resource-events version basic3-events basic3)
            response  (get-response endpoint ["=", "certname", "foo.local"] {:distinct-resources true
                                                                             :distinct-start-time 0
                                                                             :distinct-end-time (now)})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "events should be contained within distinct resource timestamps"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (get-response endpoint ["=", "certname", "foo.local"]
                                    {:distinct-resources true
                                     :distinct-start-time 0
                                     :distinct-end-time "2011-01-02T12:00:01-03:00"})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "filters (such as status) should be applied *after* the distinct list of most recent events has been built up"
      (let [expected  #{}
            response (get-response endpoint ["and" ["=" "certname" "foo.local"]
                                             ["=" "status" "success"]
                                             ["=" "resource-title" "notify, yar"]]
                                   {:distinct-resources true
                                    :distinct-start-time 0
                                    :distinct-end-time (now)})]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))

(deftestseq query-by-puppet-report-timestamp
  [[version endpoint] endpoints]

  (let [basic         (store-example-report! (:basic reports) (now))
        basic-events  (get-in reports [:basic :resource-events])

        basic3        (store-example-report! (:basic3 reports) (now))
        basic3-events (get-in reports [:basic3 :resource-events])]

    (testing "query by report start time"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (get-response endpoint ["<" "run-start-time" "2011-01-02T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by report end time"
      (let [expected  (http-expected-resource-events version basic3-events basic3)
            response  (get-response endpoint [">" "run-end-time" "2011-01-02T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by end time w/no results"
      (let [expected  #{}
            response  (get-response endpoint [">" "run-end-time" "2011-01-04T00:00:00-03:00"])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))

(deftestseq query-by-report-receive-timestamp
  [[version endpoint] endpoints]

  (let [test-start-time (ago (secs 1))

        basic           (store-example-report! (:basic reports) (now))
        basic-events    (get-in reports [:basic :resource-events])]
    (testing "query by report receive time"
      (let [expected  (http-expected-resource-events version basic-events basic)
            response  (get-response endpoint [">" "report-receive-time" (to-string test-start-time)])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))

    (testing "query by receive time w/no results"
      (let [expected  #{}
            response  (get-response endpoint ["<" "report-receive-time" (to-string test-start-time)])]
        (assert-success! response)
        (response-equal? response expected munge-event-values)))))

(def versioned-subqueries
  (omap/ordered-map
   "/v4/events"
   (omap/ordered-map
    ["and"
     ["=" "containing-class" "Foo"]
     ["in" "certname" ["extract" "certname" ["select-resources"
                                             ["=" "title" "foobar"]]]]]

    #{{:containment-path ["Foo" "" "Bar[Baz]"]
       :new-value nil
       :containing-class "Foo"
       :report-receive-time "2014-04-16T12:44:40.978Z"
       :report "e52935c051785ec6f3eeb67877aed320c90c2a88"
       :resource-title "hi"
       :property nil
       :file "bar"
       :old-value nil
       :run-start-time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run-end-time "2011-01-01T15:10:00.000Z"
       :resource-type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration-version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}}

    ["and"
     ["=" "containing-class" "Foo"]
     ["in" "certname" ["extract" "certname" ["select-facts"
                                             ["=" "value" "1.1.1.1"]]]]]

    #{{:containment-path ["Foo" "" "Bar[Baz]"]
       :new-value nil
       :containing-class "Foo"
       :report-receive-time "2014-04-16T12:44:40.978Z"
       :report "e52935c051785ec6f3eeb67877aed320c90c2a88"
       :resource-title "hi"
       :property nil
       :file "bar"
       :old-value nil
       :run-start-time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run-end-time "2011-01-01T15:10:00.000Z"
       :resource-type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration-version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}}

    ;; test vector-valued field
    ["and"
     ["=" "containing-class" "Foo"]
     ["in" ["certname", "environment"]
      ["extract" ["certname", "environment"]
       ["select-resources" ["=" "title" "foobar"]]]]]

    #{{:containment-path ["Foo" "" "Bar[Baz]"]
       :new-value nil
       :containing-class "Foo"
       :report-receive-time "2014-04-16T12:44:40.978Z"
       :report "e52935c051785ec6f3eeb67877aed320c90c2a88"
       :resource-title "hi"
       :property nil
       :file "bar"
       :old-value nil
       :run-start-time "2011-01-01T15:00:00.000Z"
       :line 2
       :status "skipped"
       :run-end-time "2011-01-01T15:10:00.000Z"
       :resource-type "Notify"
       :environment "DEV"
       :timestamp "2011-01-01T15:00:02.000Z"
       :configuration-version "a81jasj123"
       :certname "basic.catalogs.com"
       :message nil}})))

(deftestseq valid-subqueries
  [[version endpoint] endpoints]

  (let [catalog (:basic catalogs)
        certname (str (:name catalog))
        report (assoc (:basic reports) :certname certname)
        timestamp "2014-04-16T12:44:40.978Z"]
    (scf-store/add-certname! certname)
    (store-example-report! report timestamp)
    (scf-store/replace-catalog! catalog (now))
    (scf-store/add-facts! {:name certname
                           :values {"ipaddress" "1.1.1.1"}
                           :timestamp (now)
                           :environment nil
                           :producer-timestamp nil}))

  (doseq [[query results] (get versioned-subqueries endpoint)]
    (testing (str "query: " query " should match expected output")
      (is-query-result endpoint query results))))

(def versioned-invalid-subqueries
  (omap/ordered-map
   "/v4/events" (omap/ordered-map
                 ;; Extract using invalid fields should throw an error
                 ["in" "certname" ["extract" "nothing" ["select-resources"
                                                        ["=" "type" "Class"]]]]
                 #"Can't extract unknown 'resources' field 'nothing'.*Acceptable fields are.*"

                 ["in" "certname" ["extract" ["nothing" "nothing2" "certname"] ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                 #"Can't extract unknown 'resources' fields: 'nothing', 'nothing2'.*Acceptable fields are.*"

                 ;; In-query for invalid fields should throw an error
                 ["in" "nothing" ["extract" "certname" ["select-resources"
                                                        ["=" "type" "Class"]]]]
                 #"Can't match on unknown 'events' field 'nothing' for 'in'.*Acceptable fields are.*"

                 ["in" ["certname" "nothing" "nothing2"] ["extract" "certname" ["select-resources"
                                                                                ["=" "type" "Class"]]]]
                 #"Can't match on unknown 'events' fields: 'nothing', 'nothing2' for 'in'.*Acceptable fields are.*")))

(deftestseq invalid-subqueries
  [[version endpoint] endpoints]

  (doseq [[query msg] (get versioned-invalid-subqueries endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [request (get-request endpoint (json/generate-string query))
            {:keys [status body] :as result} (*app* request)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))

(def versioned-invalid-projections
  (omap/ordered-map
    "/v4/events" (omap/ordered-map
                   ;; Top level extract using invalid fields should throw an error
                   ["extract" "nothing" ["~" "certname" ".*"]]
                   #"Can't extract unknown 'events' field 'nothing'.*Acceptable fields are.*"

                   ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
                   #"Can't extract unknown 'events' fields: 'nothing', 'nothing2'.*Acceptable fields are.*")))

(deftestseq invalid-projections
  [[version endpoint] endpoints]

  (doseq [[query msg] (get versioned-invalid-projections endpoint)]
    (testing (str "query: " query " should fail with msg: " msg)
      (let [{:keys [status body] :as result} (get-response endpoint query)]
        (is (re-find msg body))
        (is (= status http/status-bad-request))))))
