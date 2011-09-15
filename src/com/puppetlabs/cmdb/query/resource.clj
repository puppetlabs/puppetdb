(ns com.puppetlabs.cmdb.query.resource
  (:require [clojure.contrib.logging :as log]
            [clj-json.core :as json]
            [clojure.string :as string]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.scf.storage :only [with-scf-connection]]
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
    (if-let [query (json/parse-string raw-query)]
      (annotated-return (not (valid-query? query)) {:annotate {:query query}})
      true)                             ; unable to parse => invalid
    false))                             ; no query => valid

(defn query->sql-where
  "Compile the vector-structured query into a seq containing the SQL \"WHERE\"
clause and all the parameters it will consume, in order."
  ([query]
     (query->sql-where query []))
  ([query params]
     {:pre  [(sequential? query) (sequential? params)]}
     (condp get (string/lower-case (first query))
       ;; joining stuff together
       #{"and" "or"}
       (let [terms (rest query)]
         (if (= 1 (count terms))
           ;; a single term degenerates to just the term.
           (query->sql-where (first terms))
           (let [terms-  (map query->sql-where (rest query))
                 sql-    (map first terms-)
                 op      (str " " (string/upper-case (first query)) " ")
                 sql     (string/join op sql-)
                 params  (reduce concat (map rest terms-))]
             (apply (partial vector sql) params))))
       ;; negation - *none* of the terms match.
       #{"not"}
       (let [terms      (rest query)
             n          (count terms)
             [sql & p]  (query->sql-where (concat ["or"] terms))
             sql        (if (= 1 n) sql (str "(" sql ")"))]
         (apply (partial vector (str "NOT " sql)) p))
       ;; comparison expressions, all having the same format.
       #{"="}
       (let [[op path value] query
             sql (str "(" (if (sequential? path) (string/join "." path) path) " = ?)")]
         [sql value]))))

(defn query->sql
  "Compile a vector-structured query into an SQL expression.
An empty query gathers all resources."
  [query]
  (if (nil? query)
    "SELECT * FROM resources"
    (let [[where & params] (query->sql-where query [])
          query (str "SELECT * FROM resources WHERE " where " ORDER BY type, title")]
      (apply (partial vector query) params))))

(defn resource-list-as-json
  "Fetch a list of resources from the database, formatting them as a
JSON array, and returning them as the body of the request."
  [request graphdata]
  (json/generate-string
   (with-scf-connection
     (query-to-vec
      (query->sql (:query graphdata))))))


(defhandler resource-list-handler
  :allowed-methods        (constantly #{:get})
  :malformed-request?     malformed-request?
  :resource-exists?       (constantly true)
  :content-types-provided (constantly {resource-list-c-t resource-list-as-json}))
