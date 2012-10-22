(ns com.puppetlabs.puppetdb.test.query.event
  (:require [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.event :as event]
            [com.puppetlabs.puppetdb.query.event :as query]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
         com.puppetlabs.puppetdb.fixtures
         com.puppetlabs.puppetdb.examples.event
         [clj-time.coerce :only [to-timestamp]]
         [clj-time.core :only [now]]))

(use-fixtures :each with-test-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Utility functions for massaging results and example data into formats that
;; can be compared for testing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expected-event-group
  [example-event-group]
  (utils/mapvals
    ;; we need to map the datetime fields to timestamp objects for comparison
    to-timestamp
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example event group object before comparison
    (dissoc example-event-group :resource-events)
    [:start-time :end-time]))

(defn expected-event-groups
  [example-event-groups]
  (map expected-event-group example-event-groups))

(defn event-groups-query-result
  [query group-id]
  (vec (->> (query/event-group-query->sql query group-id)
            (query/query-event-groups)
            ;; the example event-groups don't have a receive time (because this is
            ;; calculated by the server), so we remove this field from the response
            ;; for test comparison
            (map #(dissoc % :receive-time)))))

(defn expected-resource-event
  [example-resource-event group-id]
  (-> example-resource-event
    ;; the examples don't have the event-group-id, but the results from the
    ;; database do... so we need to munge that in.
    (assoc-in [:event-group-id] group-id)
    ;; we need to convert the datetime fields from the examples to timestamp objects
    ;; in order to compare them.
    (update-in [:timestamp] to-timestamp)))

(defn expected-resource-events
  [example-resource-events group-id]
  (set (map #(expected-resource-event % group-id) example-resource-events)))

(defn resource-events-query-result
  [query group-id]
  (set (->> (query/resource-event-query->sql query group-id)
            (query/query-resource-events))))

;; Begin tests

(deftest resource-events-retrieval
  (let [basic     (:basic resource-event-groups)
        group-id  (:group-id basic)]
    (event/validate basic)
    (scf-store/add-event-group! basic (now))

    (testing "should return all event groups if no params are passed"
      (let [expected  (expected-event-groups [basic])
            actual    (event-groups-query-result nil nil)]
        (is (= expected actual))))

    (testing "should return an event group based on id"
      (let [expected  (expected-event-groups [basic])
            actual    (event-groups-query-result nil group-id)]
        (is (= expected actual))))


    ;; TODO: break this up into events and event-groups in separate files?

    (testing "should return the list of resource events for a given event group id"
      (let [expected  (expected-resource-events (:resource-events basic) group-id)
            actual    (resource-events-query-result nil (:group-id basic))]
        (is (= expected actual))))))


