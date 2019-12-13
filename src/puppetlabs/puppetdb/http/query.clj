(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [clojure.core.match :as cm]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [clojure.set :as set]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.query.paging :refer [parse-limit
                                                      parse-offset
                                                      parse-order-by
                                                      parse-order-by-json]]
            [puppetlabs.puppetdb.pql :as pql]
            [puppetlabs.puppetdb.time :refer [to-timestamp]]
            [puppetlabs.puppetdb.utils :refer [update-when]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(def puppetdb-query-schema
  "This schema defines a PuppetDB query and its available
  parameters. In a GET request this is contained in various query
  parameters, for POST requests this should be contained in the body
  of the request"
  {(s/optional-key :query) (s/maybe [s/Any])
   (s/optional-key :ast_only) (s/maybe s/Bool)
   (s/optional-key :include_total) (s/maybe s/Bool)
   (s/optional-key :optimize_drop_unused_joins) (s/maybe s/Bool)
   (s/optional-key :pretty) (s/maybe s/Bool)
   (s/optional-key :include_facts_expiration) (s/maybe s/Bool)
   (s/optional-key :include_package_inventory) (s/maybe s/Bool)
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

(def param-spec-schema
  {(s/optional-key :optional) [s/Str]
   (s/optional-key :required) [s/Str]})

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
    [["=" "node_state" _]] criteria
    :else false))

(defn find-active-node-restriction-criteria
  "Find the first criteria in the given query that explicitly deals with
  active/deactivated nodes. Return nil if the query has no such criteria."
  [query]
  (let [criteria (query-criteria query)]
    (some is-active-node-criteria?
          (tree-seq vector? rest criteria))))

(defn paging-clauses? [query-fragments]
  (every? #(and (vector? %)
                (#{"order_by" "limit" "offset"} (first %)))
          query-fragments))

(defn add-criteria
  "Add a criteria to the given query, taking top-level 'extract' and 'from'
  forms into account."
  [crit query]
  (if-not crit
    query
    (cm/match [query]
      [["extract" columns nil]]
      ["extract" columns crit]

      [["extract" columns]]
      ["extract" columns crit]

      [["extract" columns subquery]]
      (if (= "group_by" (first subquery))
        ["extract" columns crit subquery]
        ["extract" columns ["and" subquery crit]])

      [["extract" columns subquery clauses]]
      ["extract" columns (add-criteria crit subquery) clauses]

      [["from" entity]]
      ["from" entity crit]

      [["from" entity & (paging-clauses :guard paging-clauses?)]]
      (apply vector "from" entity crit paging-clauses)

      [["from" entity subquery & (paging-clauses :guard paging-clauses?)]]
      (apply vector "from" entity (add-criteria crit subquery) paging-clauses)

      :else (if query
              ["and" query crit]
              crit))))

(defn restrict-query
  "Given a criteria that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  `restriction`"
  [restriction req]
  {:pre  [(coll? restriction)]
   :post [(are-queries-different? req %)]}
  (update-in req [:puppetdb-query :query] #(add-criteria restriction %)))

(defn restrict-query-to-active-nodes
  "Restrict the query parameter of the supplied request so that it only returns
  results for the supplied node, unless a node-active criteria is already
  explicitly specified."
  [req]
  (if (some-> req
              :puppetdb-query
              :query
              find-active-node-restriction-criteria)
    req
    (restrict-query ["=" "node_state" "active"] req)))


(defn restrict-query-to-node
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied node"
  [req]
  {:pre  [(string? (get-in req [:route-params :node]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "certname" (get-in req [:route-params :node])] req))

(defn restrict-query-to-report
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied active node"
  [req]
  {:pre  [(get-in req [:route-params :hash])]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "report" (get-in req [:route-params :hash])]
                  req))

(defn restrict-query-to-environment
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied environment"
  [req]
  {:pre  [(string? (get-in req [:route-params :environment]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "environment" (get-in req [:route-params :environment])]
                  req))

(defn restrict-query-to-producer
  "Restrict the query parameter of the supplied request so that it
   only returns results for the supplied producer"
  [req]
  {:pre  [(string? (get-in req [:route-params :producer]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "producer" (get-in req [:route-params :producer])]
                  req))

(defn restrict-fact-query-to-name
  "Restrict the query parameter of the supplied request so that it
   only returns facts with the given name"
  [req]
  {:pre  [(string? (get-in req [:route-params :fact]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "name" (get-in req [:route-params :fact])]
                  req))

(defn restrict-fact-query-to-value
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [req]
  {:pre  [(string? (get-in req [:route-params :value]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "value" (get-in req [:route-params :value])]
                  req))

(defn restrict-resource-query-to-type
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [req]
  {:pre  [(string? (get-in req [:route-params :type]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "type" (get-in req [:route-params :type])]
                  req))

(defn restrict-resource-query-to-title
  "Restrict the query parameter of the supplied request so that it
   only returns resources with the given title"
  [req]
  {:pre  [(string? (get-in req [:route-params :title]))]
   :post [(are-queries-different? req %)]}
  (restrict-query ["=" "title" (get-in req [:route-params :title])]
                  req))

(defn wrap-with-from
  "Wrap a query in a from, using the entity and any provided query"
  [entity query]
  (if query
    ["from" entity query]
    ["from" entity]))

(pls/defn-validated restrict-query-to-entity
  "Restrict the query to a particular entity, by wrapping the query in a from."
  [entity :- String]
  (fn [req]
    (update-in req [:puppetdb-query :query] #(wrap-with-from entity %))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Conversion/validation of query parameters

(defn coerce-to-boolean
  "Parses `b` to a boolean unless it's already a boolean"
  [b]
  (if (instance? Boolean b)
    b
    (Boolean/parseBoolean b)))

(pls/defn-validated validate-query-params
  "Given a set of params and a param spec, throw an error if required params
   are missing or unsupported params are present, otherwise return the params."
  [params
   param-spec :- param-spec-schema]
  (let [params (stringify-keys params)]
    (kitchensink/cond-let
      [p]
      (kitchensink/excludes-some params (:required param-spec))
      (throw (IllegalArgumentException.
              (tru "Missing required query parameter ''{0}''" p)))

      (let [diff (set/difference (kitchensink/keyset params)
                                 (set (:required param-spec))
                                 (set (:optional param-spec)))]
        (seq diff))
      (throw (IllegalArgumentException.
               (tru "Unsupported query parameter ''{0}''" (first p))))

      :else
      params)))

(pls/defn-validated convert-query-params :- puppetdb-query-schema
  "This will update a query map to contain the parsed and validated query parameters"
  [full-query param-spec]
  (-> (or full-query {})
      (validate-query-params param-spec)
      keywordize-keys
      (update-when [:ast_only] coerce-to-boolean)
      (update-when [:order_by] parse-order-by)
      (update-when [:limit] parse-limit)
      (update-when [:offset] parse-offset)
      (update-when [:include_total] coerce-to-boolean)
      (update-when [:optimize_drop_unused_joins] coerce-to-boolean)
      (update-when [:pretty] coerce-to-boolean)
      (update-when [:include_facts_expiration] coerce-to-boolean)
      (update-when [:include_package_inventory] coerce-to-boolean)
      (update-when [:distinct_resources] coerce-to-boolean)))

(defn get-req->query
  "Converts parameters of a GET request to a pdb query map"
  [{:keys [params] :as req}
   parse-fn]
  (-> params
      (update-when ["query"] parse-fn)
      (update-when ["order_by"] parse-order-by-json)
      (update-when ["counts_filter"] json/parse-strict-string true)
      (update-when ["pretty"] coerce-to-boolean)
      (update-when ["include_facts_expiration"] coerce-to-boolean)
      (update-when ["include_package_inventory"] coerce-to-boolean)
      keywordize-keys))

(defn post-req->query
  "Takes a POST body and parses the JSON to create a pdb query map"
  [req parse-fn]
  (-> (with-open [reader (-> req :body clojure.java.io/reader)]
        (json/parse-stream reader true))
      (update :query (fn [query]
                       (if (vector? query)
                         query
                         (parse-fn query))))))

(pls/defn-validated create-query-map :- puppetdb-query-schema
  "Takes a ring request map and extracts the puppetdb query from the
  request. GET and POST are accepted, all other requests throw an
  exception"
  [req param-spec parse-fn]
  (convert-query-params
   (case (:request-method req)
     :get (get-req->query req parse-fn)
     :post (post-req->query req parse-fn)
     (throw (IllegalArgumentException. (tru "PuppetDB queries must be made via GET/POST"))))
   param-spec))

(defn extract-query
  "Query handler that converts the incoming request (GET or POST)
  parameters/body to a pdb query map"
  ([handler param-spec]
   (extract-query handler param-spec pql/parse-json-query))
  ([handler param-spec parse-fn]
   (fn [{:keys [puppetdb-query] :as req}]
     (handler
      (if puppetdb-query
        req
        (let [query-map (create-query-map req param-spec parse-fn)
              pretty-print (:pretty query-map
                                    (get-in req [:globals :pretty-print]))]
          (-> req
              (assoc :puppetdb-query query-map)
              (assoc-in [:globals :pretty-print] pretty-print))))))))

(defn extract-query-pql
  [handler param-spec]
  (extract-query handler param-spec pql/parse-json-or-pql-to-ast))

(defn validate-distinct-options!
  "Validate the HTTP query params related to a `distinct_resources` query.  Return a
  map containing the validated `distinct_resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [{:keys [distinct_start_time distinct_end_time distinct_resources] :as params}]
  (let [distinct-params #{:distinct_resources :distinct_start_time
                          :distinct_end_time}
        params-present (filter distinct-params (kitchensink/keyset params))]
    (condp = (set params-present)
     #{}
      params

     distinct-params
     (let [start (to-timestamp distinct_start_time)
           end   (to-timestamp distinct_end_time)]
       (when (some nil? [start end])
         (throw (IllegalArgumentException.
                 (tru "query parameters ''distinct_start_time'' and ''distinct_end_time'' must be valid datetime strings: {0} {1}"
                      distinct_start_time distinct_end_time))))
       (merge params
              {:distinct_resources (boolean distinct_resources)
               :distinct_start_time start
               :distinct_end_time   end}))

     #{:distinct_start_time :distinct_end_time}
     (throw
      (IllegalArgumentException.
       (tru
        "''distinct_resources'' query parameter must accompany parameters ''distinct_start_time'' and ''distinct_end_time''")))
     (throw
      (IllegalArgumentException.
       (tru
        "''distinct_resources'' query parameter requires accompanying parameters ''distinct_start_time'' and ''distinct_end_time''"))))))

(defn narrow-globals
  "Reduces the number of globals to limit their reach in the codebase"
  [globals]
  (select-keys globals [:scf-read-db :warn-experimental :url-prefix :pretty-print :node-purge-ttl]))

(defn valid-query?
  [scf-read-db version query-map query-options]
  (let [{:keys [remaining-query entity query-options]}
        (qeng/user-query->engine-query version query-map (:node-purge-ttl query-options))]
    (jdbc/with-db-connection scf-read-db
      (when (qeng/query->sql remaining-query entity version query-options)
        true))))

(defn query-handler
  [version]
  (fn [{:keys [params globals puppetdb-query]}]
    (let [query-options (narrow-globals globals)]
      (if (and (:ast_only puppetdb-query) (valid-query? (:scf-read-db globals) version puppetdb-query query-options))
        (http/json-response (:query puppetdb-query))
        (qeng/produce-streaming-body version
                                (validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                                query-options)))))
