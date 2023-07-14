(ns puppetlabs.puppetdb.http.query
  "Query parameter manipulation

   Functions that aid in the parsing, serialization, and manipulation
   of PuppetDB queries embedded in HTTP parameters."
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [clojure.java.io]
            [clojure.core.match :as cm]
            [clojure.set :as set]
            [clojure.tools.logging :as log]
            [clojure.walk :refer [keywordize-keys stringify-keys]]
            [murphy :refer [try!]]
            [puppetlabs.puppetdb.query-eng :as qeng]
            [puppetlabs.puppetdb.query.monitor :as qmon]
            [puppetlabs.trapperkeeper.services.webserver.jetty9 :as jetty9]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.schema :as pls]
            [ring.util.request :as request]
            [puppetlabs.puppetdb.query.paging :refer [parse-explain
                                                      parse-limit
                                                      parse-offset
                                                      parse-order-by
                                                      parse-order-by-json]]
            [puppetlabs.puppetdb.pql :as pql]
            [puppetlabs.puppetdb.time :refer [ephemeral-now-ns to-timestamp]]
            [puppetlabs.puppetdb.utils :refer [response->channel update-when]]
            [puppetlabs.puppetdb.utils.string-formatter :refer [pprint-json-parse-exception]])
  (:import
   (clojure.lang ExceptionInfo)
   (com.fasterxml.jackson.core JsonParseException)
   (java.net HttpURLConnection)))

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
   (s/optional-key :explain) (s/maybe s/Keyword)
   (s/optional-key :include_facts_expiration) (s/maybe s/Bool)
   (s/optional-key :include_package_inventory) (s/maybe s/Bool)
   (s/optional-key :order_by) (s/maybe [[(s/one s/Keyword "field")
                                         (s/one (s/enum :ascending :descending) "order")]])
   (s/optional-key :timeout) (s/maybe s/Num) ;; seconds
   (s/optional-key :origin) (s/maybe s/Str)
   (s/optional-key :distinct_resources) (s/maybe s/Bool)
   (s/optional-key :distinct_start_time) s/Any
   (s/optional-key :distinct_end_time) s/Any
   (s/optional-key :limit) (s/maybe s/Int)
   (s/optional-key :offset) (s/maybe s/Int)
   (s/optional-key :counts_filter) s/Any
   (s/optional-key :count_by) (s/maybe s/Str)
   (s/optional-key :summarize_by) (s/maybe s/Str)})

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

(defn parse-timeout [x]
  (if (number? x)
    x
    (let [msg #(tru "Query timeout must be non-negative number, not {0}" x)]
      ;; Suspect we shouldn't be throwing IllegalArgumentException,
      ;; but that's the current approach (cf. parse-limit).
      (when-not (string? x)
        (throw (IllegalArgumentException. (msg))))
      (let [n (cond
                (re-matches #"\d+" x) (Long/parseLong x)
                (re-matches #"\d*\.\d+" x) (Double/parseDouble x)
                :else (throw (IllegalArgumentException. (msg))))]
        (if (zero? n) ##Inf n)))))

(pls/defn-validated validate-query-params
  "Given a set of params and a param spec, throw an error if required params
   are missing or unsupported params are present, otherwise return the params."
  [params
   param-spec :- param-spec-schema]
  (let [params (stringify-keys params)]
    (when-let [excluded (kitchensink/excludes-some params (:required param-spec))]
      (throw (IllegalArgumentException.
              (tru "Missing required query parameter ''{0}''" excluded))))
    (when-let [invalid (seq (set/difference (kitchensink/keyset params)
                                            (set (:required param-spec))
                                            (set (:optional param-spec))))]
      (throw (IllegalArgumentException.
              (tru "Unsupported query parameter ''{0}''" (first invalid)))))
    params))

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
      (update-when [:explain] parse-explain)
      (update-when [:include_facts_expiration] coerce-to-boolean)
      (update-when [:include_package_inventory] coerce-to-boolean)
      (update-when [:distinct_resources] coerce-to-boolean)
      (update-when [:timeout] parse-timeout)))

(defn parse-json-sequence
  "Parse a query string as JSON. Parse errors will result in an
  IllegalArgumentException. Should not be used as a parse-fn directly. Use
  parse-json-query instead"
  [query]
  (try
    (with-open [string-reader (java.io.StringReader. query)]
      (doall (json/parsed-seq string-reader)))
    (catch JsonParseException e
      (throw (IllegalArgumentException. (pprint-json-parse-exception e query))))))

(defn parse-json-query
  "Parse a query string as JSON. Multiple queries or any other
  data, after the query, will result in an IllegalArgumentException"
  [query query-uuid log-queries?]
  (when log-queries?
    (log/info (trs "Parsing PDBQuery:{0}:{1}" query-uuid (pr-str query))))
  (when query
    (let [[parsed & others] (parse-json-sequence query)]
      (when others
        (throw (IllegalArgumentException.
                (tru "Only one query may be sent in a request. Found JSON {0} after the query {1}"
                     others parsed))))
      parsed)))

(defn time-and-parse-pql
  "Time how long it takes to transform a PQL query to AST, if the time
   is greater than 1s, a warning is logged. Returns the transformed
   query."
  [query query-uuid log-queries?]
  (when log-queries?
    (log/info (trs "Parsing PDBQuery:{0}:{1}" query-uuid (pr-str query))))
  (let [start (System/nanoTime)
        result (pql/parse-pql-query query)
        elapsed (/ (- (System/nanoTime) start) 1000000.0)
        max-pql-limit 1000]
    (when (> elapsed max-pql-limit)
      (log/warn (trs "Parsing PQL took {0} ms for PDBQuery:{1}:{2}" elapsed query-uuid (pr-str query))))
    result))

(defn parse-json-or-pql-query
  "Parse a query string either as JSON or PQL to transform it to AST"
  [query query-uuid log-queries?]
  (when query
    (if (re-find #"^\s*\[" query)
      (parse-json-query query query-uuid log-queries?)
      (time-and-parse-pql query query-uuid log-queries?))))

(defn get-req->query
  "Converts parameters of a GET request to a pdb query map"
  [{:keys [params globals] query-uuid :puppetdb-query-uuid :as _req}
   parse-fn]
  (let
    [log-queries? (:log-queries globals)]
    (-> params
      (update-when ["query"] #(parse-fn % query-uuid log-queries?))
      (update-when ["order_by"] parse-order-by-json)
      (update-when ["counts_filter"] json/parse-strict-string true)
      (update-when ["pretty"] coerce-to-boolean)
      (update-when ["include_facts_expiration"] coerce-to-boolean)
      (update-when ["include_package_inventory"] coerce-to-boolean)
      keywordize-keys)))

(defn post-req->query
  "Takes a POST body and parses the JSON to create a pdb query map"
  [{query-uuid :puppetdb-query-uuid :as req} parse-fn]
  (let [log-queries? (get-in req [:globals :log-queries])
        req-body (request/body-string req)
        parsed-body (try (json/parse-string req-body true)
                      (catch JsonParseException e
                        (throw (IllegalArgumentException.
                                (pprint-json-parse-exception e req-body)))))]
    (update parsed-body :query (fn [query]
                                 (if (vector? query)
                                   query
                                   (parse-fn query query-uuid log-queries?))))))

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

(defn wrap-typical-query
  "Wrap a query endpoint request handler with the normal behaviors.
  Augment the query handler with code to extract the query from the
  incoming GET or POST and include it in the request as a pdb query
  map, and handle query timeouts."
  ([handler param-spec]
   (wrap-typical-query handler param-spec parse-json-query))
  ([handler param-spec parse-fn]
   (fn [{:keys [puppetdb-query] :as req}]
     (handler
      (if puppetdb-query
        req
        (try!
          (let [start-ns (ephemeral-now-ns) ;; capture at the top
                query-uuid (str (java.util.UUID/randomUUID))

                {:keys [pretty-print query-monitor query-timeout-default
                        query-timeout-max scf-read-db]}
                (:globals req)

                req-with-query-uuid (assoc req :puppetdb-query-uuid query-uuid)
                query-map (create-query-map req-with-query-uuid param-spec parse-fn)
                ;; Right now, query parsing (above) has no timeouts.
                ;; sync is expected to override (based on its own deadlines)
                sync-timeout (when (#{"puppet:puppetdb-sync-batch"
                                      "puppet:puppetdb-sync-summary"}
                                    (:origin query-map))
                               (:timeout query-map query-timeout-default))
                timeout (or sync-timeout
                            (min (:timeout query-map query-timeout-default)
                                 query-timeout-max))
                deadline (+ start-ns (* timeout 1000000000))
                ;; Wait one extra second for time-limited-seq and
                ;; statement timeouts since the monitor kills the
                ;; entire pg worker.
                monitor-deadline (+ deadline 1000000000)

                ;; May have no response because some tests (e.g. some
                ;; with-http-app based tests) don't add one right now.
                monitor-id (when-let [chan (and query-monitor
                                                (some-> (::jetty9/response req)
                                                        response->channel))]
                             (qmon/stop-query-at-deadline-or-disconnect query-monitor
                                                                        query-uuid
                                                                        chan
                                                                        monitor-deadline
                                                                        scf-read-db))]
            (-> req-with-query-uuid
                (assoc :puppetdb-query query-map)
                (update :globals merge
                        {:pretty-print (:pretty query-map pretty-print)
                         :query-deadline-ns deadline
                         :query-monitor query-monitor}
                        (when monitor-id
                          {:query-monitor-id monitor-id}))))
          (catch ExceptionInfo ex
            (when-not (= :puppetlabs.puppetdb.query/timeout (:kind (ex-data ex)))
              (throw ex))
            ;; Note, this will not be reached for the typical
            ;; streaming query case because after the query starts, we
            ;; return from this function with a 200, and the stream,
            ;; and further errors/timeouts have to be handled by the
            ;; generated-stream thread feeding jetty.
            (let [{:keys [id origin]} (ex-data ex)]
              (log/warn (.getMessage ex))
              (http/error-response
               (if origin
                 (tru "Query {0} from {1} exceeded timeout" id (pr-str origin))
                 (tru "Query {0} exceeded timeout" id))
               HttpURLConnection/HTTP_INTERNAL_ERROR)))))))))

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
  (select-keys globals [:scf-read-db :warn-experimental :url-prefix
                        :pretty-print :node-purge-ttl :add-agent-report-filter
                        :log-queries :query-deadline-ns :query-monitor
                        :query-monitor-id :puppetlabs.puppetdb.config/test]))

(defn valid-query?
  [scf-read-db version query-map query-options]
  (let [options (select-keys query-options [:node-purge-ttl :add-agent-report-filter])
        {:keys [remaining-query entity query-options]}
        (qeng/user-query->engine-query version query-map options)]
    (jdbc/with-db-connection scf-read-db
      (when (qeng/query->sql remaining-query entity version query-options)
        true))))

(defn query-handler
  [version]
  (fn [{:keys [params globals puppetdb-query puppetdb-query-uuid]}]
    (let [query-options (narrow-globals globals)]
      (if (and (:ast_only puppetdb-query) (valid-query? (:scf-read-db globals) version puppetdb-query query-options))
        (http/json-response (:query puppetdb-query))
        (qeng/produce-streaming-body version
                                (validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                                puppetdb-query-uuid
                                query-options)))))
