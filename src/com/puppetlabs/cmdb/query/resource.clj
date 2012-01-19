(ns com.puppetlabs.cmdb.query.resource
  (:require [com.puppetlabs.utils :as utils]
            [clojure.contrib.logging :as log]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.scf.storage :only [db-serialize]]
        [clojure.core.match.core :only [match]]
        [clothesline.protocol.test-helpers :only [annotated-return]]
        [clothesline.service.helpers :only [defhandler]]))

(def
  ^{:doc "Content type for an individual resource"}
  resource-c-t "application/vnd.com.puppetlabs.cmdb.resource+json")

(def
  ^{:doc "Content type for a list of resources"}
  resource-list-c-t "application/vnd.com.puppetlabs.cmdb.resource-list+json")


(defmulti compile-query->sql
  "Recursively compile a query into a collection of SQL operations"
  #(string/lower-case (first %)))

(defn query->sql
  "Compile a vector-structured query into an SQL expression.
An empty query gathers all resources."
  [query]
  {:pre  [(or (nil? query) (vector? query))]
   :post [(vector? %) (string? (first %))]}
  (if (nil? query)
    ["SELECT hash FROM resources"]
    (compile-query->sql query)))

(defn malformed-request?
  "Validate the JSON-encoded query for this resource, and annotate the
graphdata with the compiled data structure.  This ensures that only valid
input queries can make it through to the rest of the system."
  [_ {:keys [params] :as request} _]
  (try
    (let [sql (query->sql (json/parse-string (get params "query" "null") true))]
      (annotated-return false {:annotate {:query sql}}))
    (catch Exception e
      (annotated-return
       true
       {:headers  {"Content-Type" "application/json"}
        :annotate {:body (json/generate-string {:error (.getMessage e)})}}))))


(defn query-resources
  "Take a vector-structured query, and return a vector of resources
and their parameters which match."
  [db query]
  {:pre [(map? db)]}
  ;; REVISIT: ARGH!  So, Java *HATES* the 'IN' operation, and can't do
  ;; anything useful to fill in more than one value in it.  Options are to
  ;; use a subselect, or to manually generate the template with enough
  ;; substitution points to fill out each entry in `hashes`.
  ;;
  ;; This *bites*.  How about a wrapper, eh?  Let us try subselect for now,
  ;; and move to ClojureQL if that turns out to be hard. --daniel 2011-09-19
  (let [[sql & args] query
        resources (future
                    (let [select (partial query-to-vec
                                          (str "SELECT * FROM resources "
                                               "WHERE hash IN (" sql ")"))]
                      (sql/with-connection db
                        (apply select args))))
        params (future
                 (let [select (partial query-to-vec
                                       (str "SELECT * FROM resource_params "
                                            "WHERE resource IN (" sql ")"))]
                   (sql/with-connection db
                     (utils/mapvals
                      (partial reduce #(assoc %1 (:name %2) (json/parse-string (:value %2))) {})
                      (group-by :resource (apply select args))))))]
    (vec (map #(if-let [params (get @params (:hash %1))]
                 (assoc %1 :parameters params)
                 %1)
              @resources))))


(defn resource-list-as-json
  "Fetch a list of resources from the database, formatting them as a
JSON array, and returning them as the body of the request."
  [request graphdata]
  (let [db (get-in request [:globals :scf-db])]
    (json/generate-string (or (vec (query-resources db (:query graphdata)))
                              []))))


(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))







;;;; The SQL query compiler implementation.
(defmethod compile-query->sql "="
  [[op path value :as term]]
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "operators take two arguments, but we found %d" (dec count))))))
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
                         "? = resource_params.name AND "
                         "? = resource_params.value")
                    name (db-serialize value)]
                   ;; simple string match.
                   [(name :when string?)]
                   [(str "WHERE " name " = ?") value]
                   ;; ...else, failure
                   :else (throw (IllegalArgumentException.
                                 (str term " is not a valid query term"))))]
    (assoc sql 0 (str "(SELECT DISTINCT hash FROM resources " (first sql) ")"))))

(defn- alias-subqueries
  [queries]
  (let [ids (range (count queries))]
    (map #(format "%s resources_%d" %1 %2) queries ids)))

(defmethod compile-query->sql "and"
  [[op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map compile-query->sql terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (alias-subqueries)
                   (string/join " NATURAL JOIN ")
                   (str "SELECT DISTINCT hash FROM ")
                   (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-query->sql "or"
  [[op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map compile-query->sql terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (string/join " UNION ")
                   (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-query->sql "not"
  [[op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [[subquery & params] (compile-query->sql (cons "or" terms))
         query (->> subquery
                    (format "SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN %s rhs ON lhs.hash = rhs.hash WHERE rhs.hash IS NULL")
                    (format "(%s)"))]
    (apply vector query params)))
