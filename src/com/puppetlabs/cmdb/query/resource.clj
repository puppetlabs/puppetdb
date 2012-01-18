(ns com.puppetlabs.cmdb.query.resource
  (:require [com.puppetlabs.utils :as utils]
            [clojure.contrib.logging :as log]
            [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use clojureql.core
        [com.puppetlabs.jdbc :only [query-to-vec]]
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
  (fn [db query]
    (string/lower-case (first query))))

(defn query->sql
  "Compile a vector-structured query into an SQL expression.
An empty query gathers all resources."
  [db query]
  {:pre  [(or (nil? query) (vector? query))]
   :post [(vector? %)
          (string? (first %))
          (every? (complement coll?) (rest %))]}
  (if (nil? query)
    (-> (table :resources)
        (project [:hash])
        (distinct)
        (clojureql.core/compile db))
    (compile-query->sql db query)))

(defn malformed-request?
  "Validate the JSON-encoded query for this resource, and annotate the
graphdata with the compiled data structure.  This ensures that only valid
input queries can make it through to the rest of the system."
  [_ {:keys [params] :as request} _]
  (try
    (let [db (get-in request [:globals :scf-db])
          sql (query->sql db (json/parse-string (get params "query" "null") true))]
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
  (let [hashes (sql/with-connection db
                   (->> (query-to-vec query)
                        (map :hash)))]
    ; We have to special-case this or we get invalid queries generated later :(
    (if (empty? hashes)
      []
      (let [resources (future
                        (-> (table db :resources)
                            (select (where
                                      (in :hash hashes)))
                            (deref)))
            params (future
                     (-> (table db :resource_params)
                         (select (where
                                 (in :resource hashes)))
                         (deref)))
            params (->> @params
                     (group-by :resource)
                     (utils/mapvals
                       (partial reduce
                                #(assoc %1 (:name %2) (json/parse-string (:value %2))) {})))]

    (vec (map #(if-let [params (get params (:hash %1))]
                 (assoc %1 :parameters params)
                 %1)
              @resources))))))

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
  [db [op path value :as term]]
  (let [count (count term)]
    (if (not (= 3 count))
      (throw (IllegalArgumentException.
              (format "operators take two arguments, but we found %d" (dec count))))))
  (let [tbl (-> (table :resources)
                (distinct))
        tbl (match [path]
              ;; tag join.
              ["tag"]
                   (-> tbl
                     (join (table :resource_tags)
                           (where
                             (= :resources.hash :resource_tags.resource)))
                     (select
                       (where
                         (= :resource_tags.name value)))
                     (project [:resources.hash]))
              ;; node join.
              [["node" (field :when string?)]]
                   (-> tbl
                     (join (table :certname_resources)
                           (where
                             (= :certname_resources.resource :resources.hash)))
                     (select
                       (where
                         (= :certname_resources.certname value)))
                     (project [:resources.hash]))
              ;; param joins.
              [["parameter" (name :when string?)]]
                   (-> tbl
                     (join (table :resource_params)
                           (where
                             (= :resource_params.resource :resources.hash)))
                     (select
                       (where (and
                                (= :resource_params.name name)
                                (= :resource_params.value (db-serialize value)))))
                     (project [:resources.hash]))
              ;; simple string match.
              [(column :when string?)]
                   (-> tbl
                     (select (where
                               (= (keyword column) value)))
                     (project [:resources.hash]))
              ;; ...else, failure
              :else (throw (IllegalArgumentException.
                           (str term " is not a valid query term"))))
        [sql & params] (clojureql.core/compile tbl db)]
      (apply vector (format "(%s)" sql) params)))

(defn- alias-subqueries
  [queries]
  (let [ids (range (count queries))]
    (map #(format "%s resources_%d" %1 %2) queries ids)))

(defmethod compile-query->sql "and"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-query->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (alias-subqueries)
                   (string/join " NATURAL JOIN ")
                   (str "SELECT DISTINCT hash FROM ")
                   (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-query->sql "or"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [terms (map (partial compile-query->sql db) terms)
        params (mapcat rest terms)
        query (->> (map first terms)
                   (string/join " UNION ")
                   (format "(%s)"))]
    (apply vector query params)))

(defmethod compile-query->sql "not"
  [db [op & terms]]
  {:pre [(every? vector? terms)]
   :post [(string? (first %))
          (every? (complement coll?) (rest %))]}
  (when (empty? terms)
    (throw (IllegalArgumentException. (str op " requires at least one term"))))
  (let [[subquery & params] (compile-query->sql db (cons "or" terms))
         query (->> subquery
                    (format "SELECT DISTINCT lhs.hash FROM resources lhs LEFT OUTER JOIN %s rhs ON (lhs.hash = rhs.hash) WHERE (rhs.hash IS NULL)")
                    (format "(%s)"))]
    (apply vector query params)))
