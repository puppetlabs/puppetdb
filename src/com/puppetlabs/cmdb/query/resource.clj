(ns com.puppetlabs.cmdb.query.resource
  (:require [com.puppetlabs.utils :as utils]
            [clojure.contrib.logging :as log]
            [clojure.data.json :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.scf.storage :only [with-scf-connection]]
        [clojure.core.match.core :only [match]]
        [clothesline.protocol.test-helpers :only [annotated-return]]
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
    (if-let [query (json/read-json raw-query)]
      (annotated-return (not (valid-query? query)) {:annotate {:query query}})
      true)                             ; unable to parse => invalid
    false))                             ; no query => valid


(defmulti compile-query->sql
  "Recursively compile a query into a collection of SQL operations"
  #(string/lower-case (first %)))

(defn query->sql
  "Compile a vector-structured query into an SQL expression.
An empty query gathers all resources."
  [query]
  (if (nil? query)
    "SELECT hash FROM resources ORDER BY type, title"
    (compile-query->sql query)))

(defn query-resources
  "Take a vector-structured query, and return a vector of resources
and their parameters which match."
  [query]
  ;; REVISIT: ARGH!  So, Java *HATES* the 'IN' operation, and can't do
  ;; anything useful to fill in more than one value in it.  Options are to
  ;; use a subselect, or to manually generate the template with enough
  ;; substitution points to fill out each entry in `hashes`.
  ;;
  ;; This *bites*.  How about a wrapper, eh?  Let us try subselect for now,
  ;; and move to ClojureQL if that turns out to be hard. --daniel 2011-09-19
  (let [[sql & args] (query->sql query)
        resources (future
                    (let [select (partial query-to-vec
                                          (str "SELECT * FROM resources "
                                               "WHERE hash IN (" sql ")"))]
                      (with-scf-connection
                        (apply select args))))
        params (future
                 (let [select (partial query-to-vec
                                       (str "SELECT * FROM resource_params "
                                            "WHERE resource IN (" sql ")"))]
                   (with-scf-connection
                     (utils/mapvals
                      (partial reduce #(assoc %1 (:name %2) (:value %2)) {})
                      (group-by :resource (apply select args))))))]
    (vec (map #(if-let [params (get @params (:hash %1))]
                 (assoc %1 :parameters params)
                 %1)
              @resources))))


(defn resource-list-as-json
  "Fetch a list of resources from the database, formatting them as a
JSON array, and returning them as the body of the request."
  [request graphdata]
  (json/json-str (vec (query-resources (:query graphdata)))))


(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))







;;;; The SQL query compiler implementation.
(defmethod compile-query->sql "="
  [[op path value]]
  (let [sql (match [path]
                   ;; tag join.
                   ["tag"]
                   [(str "JOIN resource_tags ON resources.hash = resource_tags.resource "
                         "WHERE resource_tags.name = ?")
                    value]
                   ;; node join.
                   [["node" (field :when string?)]]
                   [(str "JOIN certname_resources "
                         "ON certname_resources.resource = resources.hash "
                         "WHERE certname_resources.certname = ?")
                    value]
                   ;; param joins.
                   [["parameter" (name :when string?)]]
                   [(str "JOIN resource_params "
                         "ON resource_params.resource = resources.hash "
                         "WHERE "
                         "resource_params.name = ? AND "
                         "resource_params.value = ?")
                    name value]
                   ;; simple string match.
                   [(name :when string?)]
                   [(str "WHERE " name " = ?") value])]
    (assoc sql 0 (str "(SELECT DISTINCT hash FROM resources " (first sql) ")"))))

(defn- handle-join-terms
  "Join a set of queries together with some operation; the individual
queries (of which there must be at least one) are joined safely."
  [op terms]
  (when (not (pos? (count terms)))
    (throw (IllegalArgumentException. "REVISIT: boom!")))
  (let [terms  (map compile-query->sql terms)
        sql    (str "(" (string/join (str " " op " ") (map first terms)) ")")
        params (reduce concat (map rest terms))]
    (apply (partial vector sql) params)))

(defmethod compile-query->sql "and"
  [[op & terms]] (handle-join-terms "INTERSECT" terms))
(defmethod compile-query->sql "or"
  [[op & terms]] (handle-join-terms "UNION" terms))

(defmethod compile-query->sql "not"
  [[op & terms]]
  (let [terms (query->sql (apply (partial vector "or") terms))]
    (assoc terms 0
           (str "(SELECT DISTINCT hash FROM resources EXCEPT " (first terms) ")"))))
