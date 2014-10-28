(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]))

(defn- are-queries-different?
  [req1 req2]
  (not= (get-in req1 [:params "query"])
        (get-in req2 [:params "query"])))

(defn restrict-query
  "Given a clause that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  the clause."
  [restriction {:keys [params] :as req}]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (let [restricted-query (if-let [query (params "query")]
                           (if-let [q (json/parse-string query true)]
                             (conj restriction q)
                             restriction)
                           restriction)]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query-to-active-nodes
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied node"
  [req]
  {:post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" ["node" "active"] true]]
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

(defn restrict-query-to-environment
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied environment"
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "environment" environment]]
                  req))

(defn restrict-environment-query-to-environment
  "Restricts queries to the providied environment. This differs from
  restrict-query-to-environment in that it is only used on the environments
  end-point."
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "name" environment]]
                  req))

(defn restrict-fact-query-to-name
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [fact req]
  {:pre  [(string? fact)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "name" fact]]
                  req))

(defn restrict-fact-query-to-value
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [value req]
  {:pre  [(string? value)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "value" value]]
                  req))

(defn restrict-resource-query-to-type
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [type req]
  {:pre  [(string? type)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "type" type]]
                  req))

(defn restrict-resource-query-to-title
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given title"
  [title req]
  {:pre  [(string? title)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["and"
                   ["=" "title" title]]
                  req))
