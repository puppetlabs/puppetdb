(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.core.match :as cm]))

(defn- are-queries-different?
  [req1 req2]
  (not= (get-in req1 [:params "query"])
        (get-in req2 [:params "query"])))

(defn add-criteria [crit query]
  (cm/match [query]
            [["extract" columns expr :guard nil?]]
            ["extract" columns crit]

            [["extract" columns expr :guard identity]]
            ["extract" columns ["and" expr crit]]
            :else
            (if query
              ["and" query crit]
              crit)))

(defn restrict-query
  "Given a clause that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  the clause."
  [restriction {:keys [params] :as req}]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (let [restricted-query (let [query (params "query")
                               q     (when query (json/parse-strict-string query true))]
                           (add-criteria restriction q))]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query-to-active-nodes
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied node"
  [req]
  {:post [(are-queries-different? req %)]}
  (restrict-query ["=" ["node" "active"] true]
                  req))

(defn restrict-query-to-node
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied active node"
  [node req]
  {:pre  [(string? node)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "certname" node]
                   ["=" ["node" "active"] true]]
                  req))

(defn add-extract [projections query]
  (cm/match [query]
            [["extract" columns expr :guard nil?]]
            ["extract" columns projections]

            [["extract" columns expr :guard identity]]
            ["extract" columns ["extract" projections expr]]
            :else
            (if query
              ["extract" projections query]
              ["extract" projections])))

(defn project-query
  [projections {:keys [params] :as req}]
  {:pre  [(coll? projections)]
   :post [(are-queries-different? req %)]}
  (let [restricted-query (let [query (params "query")
                               q (when query (json/parse-strict-string query true))]
                            (add-extract projections q))]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query-to-report
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied active node"
  [hash req]
  {:pre  [(string? hash)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "report" hash]
                  req))

(defn restrict-catalog-query-to-node
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied active node"
  [node req]
  {:pre  [(string? node)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "name" node]
                  req))

(defn restrict-query-to-environment
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied environment"
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "environment" environment]
                  req))

(defn restrict-environment-query-to-environment
  "Restricts queries to the providied environment. This differs from
  restrict-query-to-environment in that it is only used on the environments
  end-point."
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "name" environment]
                  req))

(defn restrict-fact-query-to-name
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [fact req]
  {:pre  [(string? fact)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "name" fact]
                  req))

(defn restrict-fact-query-to-value
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [value req]
  {:pre  [(string? value)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "value" value]
                  req))

(defn restrict-resource-query-to-type
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [type req]
  {:pre  [(string? type)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "type" type]
                  req))

(defn restrict-resource-query-to-title
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given title"
  [title req]
  {:pre  [(string? title)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "title" title]
                  req))
