(ns com.puppetlabs.puppetdb.test.http.v2.event-group
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.event :as event]
            [com.puppetlabs.utils :as utils])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.event
        [com.puppetlabs.puppetdb.testutils :only (content-type-json response-equal?)]
        [clj-time.coerce :only [to-date-time to-string]]
        [clj-time.core :only [now]]))


(use-fixtures :each with-test-db with-http-app)

;; TODO: this might be able to be abstracted out and consolidated with the similar
;; versions that currently reside in test.http.resource and test.http.event
(defn get-request
  [path query group-id]
  ;; TODO: clean this up... gotta be a better way :)
  (let [query-arg     (if query
                        {"query" (if (string? query) query (json/generate-string query))}
                        {})
        group-id-arg  (if group-id
                        {"group-id" group-id}
                        {})
        request (request :get path (merge query-arg group-id-arg))
        headers (:headers request)]
    (assoc request :headers (assoc headers "Accept" content-type-json))))

(defn get-response
  [query group-id] (*app* (get-request "/v2/event-groups" query group-id)))

(defn event-group-response
  [event-group]
  (utils/mapvals
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    to-string
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example event group object before comparison
    (dissoc event-group :resource-events)
    [:start-time :end-time]))

(defn event-groups-response
  [event-groups]
  (set (map event-group-response event-groups)))

(defn remove-receive-times
  [event-groups]
  ;; the example event-groups don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (map #(dissoc % :receive-time) event-groups))

(deftest query-by-event-group
  (let [basic (:basic resource-event-groups)]
    (event/validate basic)
    (scf-store/add-event-group! basic (now))

    ;; TODO: test invalid requests

    (testing "should return all event groups if no params are passed"
      (response-equal?
        (get-response nil (:group-id basic))
        (event-groups-response [basic])
        remove-receive-times))))
