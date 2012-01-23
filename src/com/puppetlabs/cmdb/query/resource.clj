;; ## Request Format
;;
;; The single available route is `/resources?query=<query>`. The `query`
;; parameter is a JSON array of query predicates in prefix form.
;;
;; ### Predicates
;;
;; #### =
;;
;; Resources tagged with "foo" (irrespective of other tags):
;;
;;     ["=" "tag" "foo"]
;;
;; Resources for the node "foo.example.com":
;;
;;     ["=" ["node" "<this value is ignored>"] "foo.example.com"]
;;
;; Resources whose owner parameter is "joe":
;;
;;     ["=" ["parameter" "owner"] "joe"]
;;
;; Resources whose title is "/etc/hosts"; "title" may be replaced with any legal column of the `resources` table, to query against that column:
;;
;;     ["=" "title" "/etc/hosts"]
;;
;; #### and
;;
;; Resources whose owner is "joe" and group is "people":
;;
;;     ["and" ["=" ["parameter" "owner"] "joe"]
;;            ["=" ["parameter" "group"] "people"]]
;;
;; #### or
;;
;; Resources whose owner is "joe" or "jim":
;;
;;     ["or" ["=" ["parameter" "owner"] "joe"]
;;           ["=" ["parameter" "owner"] "jim"]]
;;
;; #### not
;;
;; Resources whose owner is not "joe" AND is not "jim":
;;
;;     ["not" ["=" ["parameter" "owner"] "joe"]
;;            ["=" ["parameter" "owner"] "jim"]]
;;
;; ## Response Format
;;
;; The response is a list of resource objects, returned in JSON form. Each
;; resource object is a map of the following form:
;;
;;     {:hash       "the resource's unique hash"
;;      :type       "File"
;;      :title      "/etc/hosts"
;;      :exported   "true"
;;      :sourcefile "/etc/puppet/manifests/site.pp"
;;      :sourceline "1"
;;      :parameters {<parameter> <value>
;;                   <parameter> <value>
;;                   ...}}

(ns com.puppetlabs.cmdb.query.resource
  (:refer-clojure :exclude [case compile conj! distinct disj! drop sort take])
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
  "Recursively compile a query into a collection of SQL operations."
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
        (compile db))
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
    ;; We have to special-case this or we get invalid queries generated later
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
JSON array, and returning them as the body of the response."
  [request graphdata]
  (let [db (get-in request [:globals :scf-db])]
    (json/generate-string (or (vec (query-resources db (:query graphdata)))
                              []))))

(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))

;; ## SQL query compiler
(defmethod compile-query->sql "="
  "Compile an '=' predicate, the basic atom of a resource query. This will
produce a query that selects a set of hashes matching the predicate, which
can then be combined with connectives to build complex queries."
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
                     (join (table :catalog_resources)
                           (where
                             (= :resources.hash :catalog_resources.resource)))
                     (join (table :certname_catalogs)
                           (where
                             (= :catalog_resources.catalog :certname_catalogs.catalog)))
                     (select
                       (where
                         (= :certname_catalogs.certname value)))
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
        [sql & params] (compile tbl db)]
      (apply vector (format "(%s)" sql) params)))

(defn- alias-subqueries
  "Produce distinct aliases for a list of queries, suitable for a join
operation."
  [queries]
  (let [ids (range (count queries))]
    (map #(format "%s resources_%d" %1 %2) queries ids)))

(defmethod compile-query->sql "and"
  "Join a set of predicates together with an 'and' relationship, performing an
intersection (via natural join)."
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
  "Join a set of predicates together with an 'or' relationship, performing a
union operation."
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
  "Join a set of predicates together with a 'not' relationship, performing a
set difference. This will reject resources matching _any_ child predicate."
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
