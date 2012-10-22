;; ## Puppet event-group parsing
;;
;; Functions that handle conversion of event-groups from wire format to
;; internal PuppetDB format, including validation.

(ns com.puppetlabs.puppetdb.event
  (:use [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.utils :only [datetime? string-or-nil?]]
        [com.puppetlabs.puppetdb.query.utils :only [wire-to-sql]])
  (:require [cheshire.core :as json]
            [com.puppetlabs.utils :as utils]
            [clojure.string :as s]))


(defn validate-map
  "A utility function for validating the contents of a map.  Throws
  `IllegalArgumentException` if the contents of the map are not valid.

  Requires three arguments:

  * `desc`: A description of the object/map that you are validating; this will
    be used to create a descriptive error message in the event that the map is
    not valid.
  * `m`: The map to validate.
  * `key-defs`: The definitions of the keys should exist in the map, and how they
    should be validated.  This argument should be a list of triples.  Each triple
    should consist of:
      * the key to validate
      * a predicate function to apply to the value of that key in the map to test
        its validity, and
      * a string describing the expected type of the value, used to create an
        more useful error message explaining why the validation failed if it does."
  [desc m key-defs]
  (doseq [[required-key validate-fn type-desc] key-defs]
    (when-not (contains? m required-key)
      (throw (IllegalArgumentException. (format "%s is missing required key %s" desc required-key))))
    (when-not (validate-fn (required-key m))
      (throw (IllegalArgumentException.
               (format "%s data is invalid for key %s; expected type '%s', got '%s'"
                 desc required-key type-desc (required-key m)))))))

(defn validate-meta
  "Validate that the event group data structure contains all of the required metadata."
  [event-group]
  (validate-map "Event group" event-group
      [[:group-id    string?     "string"]
       [:start-time  datetime?   "datetime"]
       [:end-time    datetime?   "datetime"]])
  event-group)

(defn validate-resource-event
  "Validate a resource event data structure."
  [resource-event]
  (validate-map "Resource event" resource-event
      [[:certname          string?                 "string"]
       [:status            string?                 "string"]
       [:timestamp         datetime?               "datetime"]
       [:resource-type     string?                 "string"]
       [:resource-title    string?                 "string"]
       [:property-name     string-or-nil?          "string"]
       [:property-value    string-or-nil?          "string"]
       [:previous-value    string-or-nil?          "string"]
       [:message           string-or-nil?          "string"]
       ]))

(defn validate-resource-events
  "Verify that any resource events contained in the event group data structure
  are valid."
  [event-group]
  (doseq [resource-event (:resource-events event-group)]
    (validate-resource-event resource-event))
  event-group)

(defn validate
  "Validate an event group data structure.  Throws IllegalArgumentException if
  the event group is invalid."
  [event-group]
  (-> event-group
      validate-meta
      validate-resource-events))

(defn parse-from-json-string
  "Parse an event group from a json string.  Validates the resulting data structure
  and ensures that it conforms to the puppetdb wire format; throws
  IllegalArgumentException if the data is not valid."
  [s]
  {:pre  [(string? s)]
   :post [(map? %)]}
  (validate (json/parse-string s true)))

(defn resource-event-to-sql
  "Given a resource event object in its puppetdb wire format, convert the data
  structure into a format suitable for handing off to JDBC function such as
  `insert-records`.

  Also requires a 'group-id' argument, which should contain the group id of the
  event group that this event is associated with."
  [resource-event group-id]
  (-> resource-event
    (assoc-in [:event-group-id] group-id)
    (wire-to-sql {[:timestamp] to-timestamp})))
