(ns com.puppetlabs.cmdb.query.resource
  (:require [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]))

(def
  ^{:doc "Content type for an individual resource"}
  resource-c-t "application/vnd.com.puppetlabs.cmdb.resource+json")

(def
  ^{:doc "Content type for a list of resources"}
  resource-list-c-t "application/vnd.com.puppetlabs.cmdb.resource-list+json")


(defn valid-query?
  "Determine if the query structure is valid or invalid."
  [query]
  (when (and (vector? query) (string? (first query)))
    (condp get (string/lower-case (first query))
      ;; negation, conjuctions, disjunctions, and related combination operations.
      #{"not" "and" "or"}
      (let [terms (rest query)]
        (and (pos? (count terms)) (every? valid-query? terms)))
      ;; comparison expressions, all having the same format.
      #{"="}
      (when (= 3 (count query))
        (let [[op path value] query]
          ;; REVISIT: This should validate the path contains valid fields, not
          ;; just that we have strings were they should be.
          (and (if (sequential? path) (every? string? path) (string? path))
               (string? value))))
      ;; ...else, fail.
      nil)))

(defn malformed-request?
  "Validate the JSON-encoded query for this resource, and annotate the
graphdata with the compiled data structure.  This ensures that only valid
input queries can make it through to the rest of the system."
  [_ {:keys [params] :as request} _]
  (if-let [raw-query (get params "query")]
    (if-let [query (json/parse-string raw-query)]
      (annotated-return (not (valid-query? query)) {:annotate {:query query}})
      true)                             ; unable to parse => invalid
    false))                             ; no query => valid


(defn resource-list-as-json
  "Fetch a list of resources from the database, formatting them as a
JSON array, and returning them as the body of the request."
  [request graphdata]
  "REVISIT: Not implemented")

(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))
