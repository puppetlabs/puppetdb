(ns puppetlabs.puppetdb.http.handlers
  (:require [puppetlabs.puppetdb.http :as http]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body
                                                   stream-query-result]]
            [puppetlabs.puppetdb.middleware :refer [validate-no-query-params
                                                    validate-query-params
                                                    parent-check]]
            [puppetlabs.comidi :as cmdi]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.catalogs :refer [catalog-query-schema]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [clojure.walk :refer [keywordize-keys]]))

(def handler-schema (s/=> s/Any {s/Any s/Any}))
(def params-schema {(s/optional-key :optional) [s/Str]
                    (s/optional-key :required) [s/Str]})

;; General route/handler construction functions

(pls/defn-validated extract-query :- bidi-schema/RoutePair
  ([routes :- bidi-schema/RoutePair]
   (extract-query {:optional paging/query-params} routes))
  ([param-spec :- params-schema
    routes :- bidi-schema/RoutePair]
   (cmdi/wrap-routes routes #(http-q/extract-query % param-spec))))

(pls/defn-validated append-handler :- bidi-schema/RoutePair
  [route :- bidi-schema/RoutePair
   handler-to-append :- handler-schema]
  (cmdi/wrap-routes route #(comp % handler-to-append)))

(pls/defn-validated create-handler :- handler-schema
  "Creates a new query handler for the given `entity` and `version`."
  [version :- s/Keyword
   entity :- s/Str
   & handler-fns :- [handler-schema]]
  (apply comp
         (http-q/query-handler version)
         (http-q/restrict-query-to-entity entity)
         handler-fns))

(pls/defn-validated wrap-with-parent-check :- bidi-schema/RoutePair
  "Wraps all handlers found in `route` with the parent-check middleware"
  [route :- bidi-schema/RoutePair
   version :- s/Keyword
   entity :- s/Keyword
   route-param-key :- s/Keyword]
  (cmdi/wrap-routes route #(parent-check % version entity route-param-key)))

(pls/defn-validated url-decode :- s/Str
  [x :- s/Str]
  (java.net.URLDecoder/decode x))

(pls/defn-validated decode-route-params :- {s/Any s/Any}
  "Bidi will not decode URL route segments. Calling this handler will
  URL decode all route-params"
  [{:keys [route-params] :as req} :- {s/Any s/Any}]
  (if route-params
    (update req :route-params #(kitchensink/mapvals url-decode %))
    req))

(pls/defn-validated experimental-index-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   {:optional paging/query-params
    :required ["query"]}
   (cmdi/ANY "" []
             (-> (http-q/query-handler version)
                 (http/experimental-warning "The root endpoint is experimental")))))

(defn narrow-globals
  "Reduces the number of globals to limit their reach in the codebase"
  [globals]
  (select-keys globals [:scf-read-db :warn-experimental :url-prefix :pretty-print]))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity]
  (fn [{:keys [globals route-params]}]
    (let [query ["from" entity ["=" "hash" (:hash route-params)]]]
      (produce-streaming-body version {:query query}
                              (narrow-globals globals)))))

(defn route-param [param-name]
  [#"[\w%\.~-]*" param-name])

;; Handlers checking for a single entity

(defn status-response
  "Executes `query` and if a result is found, calls `found-fn` with
  that result, returns 404 otherwise."
  [version query globals found-fn not-found-response]
  (if-let [query-result (first (stream-query-result version query {} globals))]
    (http/json-response (found-fn query-result))
    not-found-response))

(defn catalog-status
  "Produces a response body for a request to retrieve the catalog for the node in route-params"
  [version]
  (fn [{:keys [globals route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "catalogs" ["=" "certname" node]]
                       (narrow-globals globals)
                       #(s/validate catalog-query-schema
                                    (kitchensink/mapvals sutils/parse-db-json [:edges :resources] %))
                       (http/status-not-found-response "catalog" node)))))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for the node in route-params"
  [version]
  (fn [{:keys [globals route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "factsets" ["=" "certname" node]]
                       (narrow-globals globals)
                       identity
                       (http/status-not-found-response "factset" node)))))

(defn node-status
  "Produce a response body for a single environment."
  [version]
  (fn [{:keys [globals route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "nodes" ["=" "certname" node]]
                       (narrow-globals globals)
                       identity
                       (http/status-not-found-response "node" node)))))

(defn environment-status
  "Produce a response body for a single environment."
  [version]
  (fn [{:keys [globals route-params]}]
    (let [environment (:environment route-params)]
      (status-response version
                       ["from" "environments" ["=" "name" environment]]
                       (narrow-globals globals)
                       identity
                       (http/status-not-found-response "environment" environment)))))

;; Routes

(pls/defn-validated events-routes :- bidi-schema/RoutePair
  "Ring app for querying events"
  [version :- s/Keyword]
  (extract-query
   {:optional (concat
               ["query"
                "distinct_resources"
                "distinct_start_time"
                "distinct_end_time"]
               paging/query-params)}
   (cmdi/routes
    (cmdi/ANY "" []
              (create-handler version "events")))))

(pls/defn-validated reports-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (cmdi/routes
   (extract-query
    (cmdi/ANY "" []
              (create-handler version "reports")))
   
   (cmdi/context ["/" :hash "/events"]
                 (-> (events-routes version)
                     (append-handler (comp http-q/restrict-query-to-report decode-route-params))
                     (wrap-with-parent-check version :report :hash)))
   
   (cmdi/ANY ["/" :hash "/metrics"] []
             (-> (report-data-responder version "report_metrics")
                 (comp decode-route-params)
                 (parent-check version :report :hash)
                 validate-no-query-params))
   
   (cmdi/ANY ["/" :hash "/logs"] []
             (-> (report-data-responder version "report_logs")
                 (comp decode-route-params)
                 (parent-check version :report :hash)
                 validate-no-query-params))))

(pls/defn-validated resources-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   {:optional paging/query-params}
   (cmdi/routes
    (cmdi/ANY "" []
              (create-handler version "resources"  http-q/restrict-query-to-active-nodes))
    
    (cmdi/context ["/" (route-param :type)]
                  (cmdi/ANY "" []
                            (create-handler version "resources"
                                            http-q/restrict-resource-query-to-type
                                            http-q/restrict-query-to-active-nodes
                                            decode-route-params))
                  (cmdi/ANY ["/" (route-param :title)] []
                            (create-handler version "resources"
                                            http-q/restrict-resource-query-to-title
                                            http-q/restrict-resource-query-to-type
                                            http-q/restrict-query-to-active-nodes
                                            decode-route-params))))))

(pls/defn-validated edge-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   (cmdi/ANY "" []
             (create-handler version "edges" http-q/restrict-query-to-active-nodes))))

(pls/defn-validated catalog-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   {:optional paging/query-params}
   (cmdi/routes

    (cmdi/ANY "" []
              (create-handler version "catalogs"))

    (cmdi/context ["/" (route-param :node)]
                  (cmdi/ANY "" []
                            (comp (catalog-status version)
                                  decode-route-params))

                  (cmdi/context "/edges"
                                (-> (edge-routes version)
                                    (append-handler decode-route-params)
                                    (wrap-with-parent-check version :catalog :node)))
                                
                  (cmdi/context "/resources"
                                (-> (resources-routes version)
                                    (append-handler (comp http-q/restrict-query-to-node
                                                          decode-route-params)) 
                                    (wrap-with-parent-check version :catalog :node)))))))

(pls/defn-validated facts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (let [param-spec {:optional paging/query-params}]
    (extract-query
     {:optional paging/query-params}
     (cmdi/routes
      (cmdi/ANY "" []
                (create-handler version "facts" http-q/restrict-query-to-active-nodes))

      (cmdi/context ["/" (route-param :fact)]
                                  
                    (cmdi/ANY "" []
                              (create-handler version "facts"
                                              http-q/restrict-fact-query-to-name 
                                              http-q/restrict-query-to-active-nodes
                                              decode-route-params))

                    (cmdi/ANY ["/" (route-param :value)] []
                              (create-handler version "facts"
                                              http-q/restrict-fact-query-to-name
                                              http-q/restrict-fact-query-to-value
                                              http-q/restrict-query-to-active-nodes
                                              decode-route-params)))))))

(pls/defn-validated factset-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (let [param-spec {:optional paging/query-params}]
    (extract-query
     {:optional paging/query-params}
     (cmdi/routes
      (cmdi/ANY "" []
                (create-handler version "factsets" http-q/restrict-query-to-active-nodes))

      (cmdi/context ["/" :node]
                    (cmdi/ANY "" []
                              (comp (factset-status version)
                                    decode-route-params))

                    (cmdi/ANY "/facts" []
                              (-> (create-handler version "factsets" http-q/restrict-query-to-node)
                                  (comp decode-route-params)
                                  (parent-check version :factset :node))))))))

(pls/defn-validated fact-names-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query {:optional paging/query-params}
                 (cmdi/ANY "" []
                           (comp
                            (fn [{:keys [params globals puppetdb-query]}]
                              (let [puppetdb-query (assoc-when puppetdb-query :order_by [[:name :ascending]])]
                                (produce-streaming-body
                                 version
                                 (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                                 (narrow-globals globals))))
                            (http-q/restrict-query-to-entity "fact_names")))))

(pls/defn-validated node-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (let [param-spec {:optional paging/query-params}]
    (extract-query
     {:optional paging/query-params}
     (cmdi/routes
      (cmdi/ANY "" []
                (create-handler version "nodes" http-q/restrict-query-to-active-nodes))
      (cmdi/context ["/" (route-param :node)]
                    (cmdi/ANY "" []
                              (-> (node-status version)
                                  (comp decode-route-params)
                                  validate-no-query-params))
                    
                    (cmdi/context "/facts"
                                  (-> (facts-routes version)
                                      (append-handler (comp http-q/restrict-query-to-node decode-route-params))
                                      (wrap-with-parent-check version :node :node)))
                    (cmdi/context "/resources"
                                  (-> (resources-routes version)
                                      (append-handler (comp http-q/restrict-query-to-node decode-route-params))
                                      (wrap-with-parent-check version :node :node))))))))

(pls/defn-validated environments-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (extract-query
      (cmdi/ANY "" []
                (create-handler version "environments")))
     (cmdi/context ["/" (route-param :environment)]
                   (cmdi/ANY "" []
                             (validate-no-query-params (environment-status version)))
                   
                   (wrap-with-parent-check
                    (cmdi/routes
                     (extract-query
                      (cmdi/context "/facts"
                                    (-> (facts-routes version)
                                        (append-handler http-q/restrict-query-to-environment))))
                     
                     (extract-query
                      (cmdi/context "/resources"
                                    (-> (resources-routes version)
                                        (append-handler http-q/restrict-query-to-environment))))

                     (extract-query
                      (cmdi/context "/reports"
                                    (-> (reports-routes version)
                                        (append-handler http-q/restrict-query-to-environment))))

                     (extract-query
                      {:optional (concat
                                  ["query"
                                   "distinct_resources"
                                   "distinct_start_time"
                                   "distinct_end_time"]
                                  paging/query-params)}

                      (cmdi/context "/events"
                                    (-> (events-routes version)
                                        (append-handler http-q/restrict-query-to-environment)))))
                    version :environment :environment)))))

(pls/defn-validated fact-contents-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   (cmdi/ANY "" []
             (create-handler version "fact_contents" http-q/restrict-query-to-active-nodes))))

(pls/defn-validated fact-path-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   (cmdi/ANY "" []
             (create-handler version "fact_paths"))))

(pls/defn-validated event-counts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   {:required ["summarize_by"]
    :optional (concat ["counts_filter" "count_by"
                       "distinct_resources" "distinct_start_time"
                       "distinct_end_time"]
                      paging/query-params)}
   (cmdi/ANY "" []
             (create-handler version "event_counts"))))

(pls/defn-validated agg-event-counts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   {:required ["summarize_by"]
    :optional ["query" "counts_filter" "count_by"
               "distinct_resources" "distinct_start_time"
               "distinct_end_time"]}
   (cmdi/ANY "" []
             (create-handler version
                             "aggregate_event_counts"))))

