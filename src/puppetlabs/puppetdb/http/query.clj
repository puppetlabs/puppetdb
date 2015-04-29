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

(defn query-criteria
  "Extract the 'criteria' (select) part of the given query"
  [query]
  (cm/match [query]
    [["extract" _ expr]] expr
    :else query))

(defn is-active-node-criteria? [criteria]
  (cm/match [criteria]
    [["=" ["node" "active"] _]] criteria
    :else false))

(defn find-active-node-restriction-criteria
  "Find the first criteria in the given query that explicitly deals with
  active/deactivated nodes. Return nil if the query has no such criteria."
  [query]
  (let [criteria (query-criteria query)]
    (some is-active-node-criteria?
          (tree-seq vector? rest criteria))))

(defn add-criteria
  "Add a criteria to the given query, taking top-level extract queries into
  account."
  [crit query]
  (cm/match [query]
    [["extract" columns nil]]
    ["extract" columns crit]

    [["extract" columns subquery]]
    ["extract" columns ["and" subquery crit]]

    [["extract" columns subquery clauses]]
    ["extract" columns ["and" subquery crit] clauses]

    :else (if query
            ["and" query crit]
            crit)))

(defn restrict-query
  "Given a criteria that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  the criteria."
  [restriction {:keys [params] :as req}]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (let [restricted-query (let [query (params "query")
                               q     (when query (json/parse-strict-string query true))]
                           (add-criteria restriction q))]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query-to-active-nodes
  "Restrict the query parameter of the supplied request so that it only returns
  results for the supplied node, unless a node-active criteria is already
  explicitly specified."
  [req]
  (if (some-> (get-in req [:params "query"])
              (json/parse-strict-string true)
              find-active-node-restriction-criteria)
    req
    (restrict-query ["=" ["node" "active"] true] req)))


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
