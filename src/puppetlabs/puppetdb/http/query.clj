(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.core.match :as cm]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body produce-streaming-body']]
            [net.cgrand.moustache :refer [app]]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.paging :refer [parse-limit' parse-offset' parse-order-by']]
            [puppetlabs.puppetdb.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def puppetdb-query-schema
  "This schema defines a PuppetDB query and its available
  parameters. In a GET request this is contained in various query
  parameters, for POST requests this should be contained in the body
  of the request"
  {(s/optional-key :query) (s/maybe [s/Any])
   :count? s/Bool
   (s/optional-key :order_by) (s/maybe [[(s/one s/Keyword "field")
                                         (s/one (s/enum :ascending :descending) "order")]])
   (s/optional-key :limit) (s/maybe s/Int)
   (s/optional-key :offset) (s/maybe s/Int)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Query munging functions

(defn- are-queries-different?
  "DEPRECATED - only works on GET requests, will be replaced by are-queries-different?'"
  {:deprecated "3.0.0"}
  [req1 req2]
  (not= (get-in req1 [:params "query"])
        (get-in req2 [:params "query"])))

(defn- are-queries-different?'
  [req1 req2]
  (not= (:puppetdb-query req1)
        (:puppetdb-query req2)))

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
  "DEPRECATED - this function only works on GETs, will be replaced by restrict-query'

  Given a criteria that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  the criteria."
  {:deprecated "3.0.0"}
  [restriction {:keys [params] :as req}]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (let [restricted-query (let [query (params "query")
                               q     (when query (json/parse-strict-string query true))]
                           (add-criteria restriction q))]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query'
  "Given a criteria that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  `restriction`"
  [restriction req]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different?' req %)]}
  (update-in req [:puppetdb-query :query] #(add-criteria restriction %)))

(defn restrict-query-to-active-nodes
  "DEPRECATED - only works on GET requests, will be replaced by restrict-query-to-active-nodes'

  Restrict the query parameter of the supplied request so that it only returns
  results for the supplied node, unless a node-active criteria is already
  explicitly specified."
  {:deprecated "3.0.0"}
  [req]
  (if (some-> (get-in req [:params "query"])
              (json/parse-strict-string true)
              find-active-node-restriction-criteria)
    req
    (restrict-query ["=" ["node" "active"] true] req)))

(defn restrict-query-to-active-nodes'
  "Restrict the query parameter of the supplied request so that it only returns
  results for the supplied node, unless a node-active criteria is already
  explicitly specified."
  [req]
  (if (some-> req
              :puppetdb-query
              :query
              find-active-node-restriction-criteria)
    req
    (restrict-query' ["=" ["node" "active"] true] req)))


(defn restrict-query-to-node
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied node"
  [node req]
  {:pre  [(string? node)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "certname" node] req))

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

(defn restrict-query-to-environment'
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied environment"
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different?' req %)]}
  (restrict-query' ["=" "environment" environment]
                   req))

(defn restrict-fact-query-to-name
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [fact req]
  {:pre  [(string? fact)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "name" fact]
                  req))

(defn restrict-fact-query-to-name'
  "Restrict the query parameter of the supplied request so that it
   only returns facts with the given name"
  [fact req]
  {:pre  [(string? fact)]
   :post [(are-queries-different?' req %)]}
  (restrict-query' ["=" "name" fact]
                   req))

(defn restrict-fact-query-to-value
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [value req]
  {:pre  [(string? value)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "value" value]
                  req))

(defn restrict-fact-query-to-value'
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [value req]
  {:pre  [(string? value)]
   :post [(are-queries-different?' req %)]}
  (restrict-query' ["=" "value" value]
                  req))

(defn restrict-resource-query-to-type
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [type req]
  {:pre  [(string? type)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "type" type]
                  req))

(defn restrict-resource-query-to-type'
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [type req]
  {:pre  [(string? type)]
   :post [(are-queries-different?' req %)]}
  (restrict-query' ["=" "type" type]
                  req))

(defn restrict-resource-query-to-title
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given title"
  [title req]
  {:pre  [(string? title)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "title" title]
                  req))

(defn restrict-resource-query-to-title'
  "Restrict the query parameter of the supplied request so that it
   only returns resources with the given title"
  [title req]
  {:pre  [(string? title)]
   :post [(are-queries-different?' req %)]}
  (restrict-query' ["=" "title" title]
                   req))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion/validation of query parameters

(defn coerce-to-boolean
  "Parses `b` to a boolean unless it's already a boolean"
  [b]
  (if (instance? Boolean b)
    b
    (Boolean/parseBoolean b)))

(defn count?
  "If the original query contains the include_total w/o a a value, it
  will show up here as nil.  We assume that in that case, the caller
  intended to use it as a flag"
  [query-map]
  (let [total (get query-map :include_total ::not-found)]
    (if (= total ::not-found)
      false
      (coerce-to-boolean total))))

(pls/defn-validated convert-query-params :- puppetdb-query-schema
  "This will update a query map to contain the parsed and validated query parameters"
  [{:keys [order_by limit offset] :as full-query}]
  (-> full-query
      (update :order_by parse-order-by')
      (update :limit parse-limit')
      (update :offset parse-offset')
      (assoc :count? (count? full-query))
      (dissoc :include_total)))

(defn get-req->query
  "Converts parameters of a GET request to a pdb query map"
  [{:keys [params] :as req}]
  (conj {:query (json/parse-strict-string (get params "query") true)
         :order_by (json/parse-strict-string (get params "order_by") true)
         :limit (get params "limit")
         :offset (get params "offset")}
        (let [include-total? (get params "include_total" ::not-found)]
          (when (not= include-total? ::not-found)
            [:include_total include-total?]))))

(defn post-req->query
  "Takes a POST body and parses the JSON to create a pdb query map"
  [req]
  (with-open [reader (-> req :body clojure.java.io/reader)]
    (json/parse-stream reader true)))

(pls/defn-validated create-query-map :- puppetdb-query-schema
  "Takes a ring request map and extracts the puppetdb query from the
  request. GET and POST are accepted, all other requests throw an
  exception"
  [req]
  (convert-query-params
   (case (:request-method req)
     :get (get-req->query req)
     :post (post-req->query req)
     (throw (IllegalArgumentException. "PuppetDB queries must be made via GET/POST")))))

(defn extract-query
  "Query handler that converts the incoming request (GET or POST)
  parameters/body to a pdb query map"
  [handler]
  (fn [{:keys [request-method body params puppetdb-query] :as req}]
    (handler (assoc req :puppetdb-query (create-query-map req)))))

(defn query-route
  "Returns a route for querying PuppetDB that supports the standard
  paging parameters. Also accepts GETs and POSTs. Composes
  `optional-handlers` with the middleware function that executes the
  query."
  [entity version & optional-handlers]
  (app
   extract-query
   (apply comp
          (fn [{:keys [params globals puppetdb-query]}]
            (produce-streaming-body'
             entity
             version
             puppetdb-query
             (:scf-read-db globals)
             (:url-prefix globals)))
          optional-handlers)))
