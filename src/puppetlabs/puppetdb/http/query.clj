(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.walk :refer [keywordize-keys]]
            [clojure.core.match :as cm]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [net.cgrand.moustache :refer [app]]
            [schema.core :as s]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.paging :refer [parse-limit' parse-offset' parse-order-by']]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.utils :as utils]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def puppetdb-query-schema
  "This schema defines a PuppetDB query and its available
  parameters. In a GET request this is contained in various query
  parameters, for POST requests this should be contained in the body
  of the request"
  {(s/optional-key :query) (s/maybe [s/Any])
   (s/optional-key :include_total) (s/maybe s/Bool)
   (s/optional-key :order_by) (s/maybe [[(s/one s/Keyword "field")
                                         (s/one (s/enum :ascending :descending) "order")]])
   (s/optional-key :distinct_resources) (s/maybe s/Bool)
   (s/optional-key :distinct_start_time) s/Any
   (s/optional-key :distinct_end_time) s/Any
   (s/optional-key :limit) (s/maybe s/Int)
   (s/optional-key :counts_filter) s/Any
   (s/optional-key :count_by) (s/maybe s/Str)
   (s/optional-key :summarize_by) (s/maybe s/Str)
   (s/optional-key :offset) (s/maybe s/Int)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Query munging functions

(def experimental-entities
  #{:event-counts :aggregate-event-counts})

(defn- are-queries-different?
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
  "Given a criteria that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  `restriction`"
  [restriction req]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (update-in req [:puppetdb-query :query] #(add-criteria restriction %)))

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
    (restrict-query ["=" ["node" "active"] true] req)))


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

(defn restrict-query-to-environment
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied environment"
  [environment req]
  {:pre  [(string? environment)]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "environment" environment]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion/validation of query parameters

(defn coerce-to-boolean
  "Parses `b` to a boolean unless it's already a boolean"
  [b]
  (if (instance? Boolean b)
    b
    (Boolean/parseBoolean b)))

(pls/defn-validated convert-query-params :- puppetdb-query-schema
  "This will update a query map to contain the parsed and validated query parameters"
  [full-query]
  (-> full-query
      (update :order_by parse-order-by')
      (update :limit parse-limit')
      (update :offset parse-offset')
      (utils/update-when [:include_total] coerce-to-boolean)))

(defn get-req->query
  "Converts parameters of a GET request to a pdb query map"
  [{:keys [params] :as req}]
  (conj {:query (json/parse-strict-string (get params "query") true)
         :order_by (json/parse-strict-string (get params "order_by") true)
         :counts_filter (get params "counts_filter")
         :limit (get params "limit")
         :offset (get params "offset")
         :summarize_by (get params "summarize_by")}
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

(defn validate-distinct-options!
  "Validate the HTTP query params related to a `distinct_resources` query.  Return a
  map containing the validated `distinct_resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params]
  (let [distinct-params-names #{"distinct_resources" "distinct_start_time" "distinct_end_time"}
        {:keys [distinct_start_time distinct_end_time] :as distinct-params}
        (select-keys params distinct-params-names)]
    (println "PARAMS ARE" params)
    (condp = (kitchensink/keyset distinct-params)
     #{}
      params

     distinct-param-names
     (let [start (to-timestamp distinct_start_time)
           end   (to-timestamp distinct_end_time)]
       (when (some nil? [start end])
         (throw (IllegalArgumentException.
                 (str "query parameters 'distinct_start_time' and 'distinct_end_time' must be valid datetime strings: "
                      distinct_start_time " " distinct_end_time))))
       (merge params
              {:distinct_resources (boolean distinct_resources)
               :distinct_start_time start
               :distinct_end_time   end}))

     #{:distinct_start_time :distinct_end_time}
     (throw
       (IllegalArgumentException.
         "'distinct_resources' query parameter must accompany parameters 'distinct_start_time' and 'distinct_end_time'"))
     (throw
       (IllegalArgumentException.
         "'distinct_resources' query parameter requires accompanying parameters 'distinct_start_time' and 'distinct_end_time'")))))

(defn warn-experimental
  [entity {:keys [product-name]}]
  (when (and (= "puppetdb" product-name)
             (contains? experimental-entities entity))
    (log/warn (format
                "The %s endpoint is experimental and may be altered or removed in the future."
                (name entity)))))

(defn query-route
  "Returns a route for querying PuppetDB that supports the standard
   paging parameters. Also accepts GETs and POSTs. Composes
   `optional-handlers` with the middleware function that executes the
   query."
  [entity version param-spec & optional-handlers]
  (app
    (extract-query param-spec)
    (apply comp
           (fn [{:keys [params globals puppetdb-query]}]
             (warn-experimental entity globals)
             (produce-streaming-body
               entity
               version
               (validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
               (:scf-read-db globals)
               (:url-prefix globals)))
           optional-handlers)))
