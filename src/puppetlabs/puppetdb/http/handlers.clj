(ns puppetlabs.puppetdb.http.handlers
  (:require [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http :as http]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body
                                                   stream-query-result]]
            [puppetlabs.puppetdb.middleware :refer [merge-param-specs
                                                    params-schema
                                                    validate-query-params
                                                    parent-check
                                                    handler-schema]]
            [puppetlabs.comidi :as cmdi]
            [schema.core :as s]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.catalogs :refer [catalog-query-schema]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.i18n.core :refer [trs]]))

;; General route/handler construction functions

(pls/defn-validated extract-query :- bidi-schema/RoutePair
  [param-spec :- params-schema
   routes :- bidi-schema/RoutePair]
  (cmdi/wrap-routes routes #(http-q/extract-query % param-spec)))

(pls/defn-validated append-handler :- bidi-schema/RoutePair
  [route :- bidi-schema/RoutePair
   handler-to-append :- handler-schema]
  (cmdi/wrap-routes route #(comp % handler-to-append)))

(pls/defn-validated create-query-handler :- handler-schema
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

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity]
  (fn [{:keys [globals route-params]}]
    (let [query ["from" entity ["=" "hash" (:hash route-params)]]]
      (produce-streaming-body version {:query query}
                              (http-q/narrow-globals globals)))))

(defn route-param [param-name]
  [#"[\w%\.~:-]*" param-name])

;; Handlers checking for a single entity

(def global-engine-params
  "Parameters that should always be forwarded from the incoming query to
  the engine."
  [:optimize_drop_unused_joins :include_facts_expiration :explain])

(defn status-response
  "Executes `query` and if a result is found, calls `found-fn` with
  that result, returns 404 otherwise."
  ([version query globals found-fn not-found-response query-params]
   (let [[item & items] (stream-query-result version query query-params globals)]
     (when (seq items) ;; For now, just log
       (log/error (trs "more than one item returned for singular query")))
     (if item
       (http/json-response (found-fn item))
       not-found-response))))

(defn catalog-status
  "Produces a response body for a request to retrieve the catalog for the node in route-params"
  [version]
  (fn [{:keys [globals puppetdb-query route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "catalogs" ["=" "certname" node]]
                       (http-q/narrow-globals globals)
                       #(s/validate catalog-query-schema
                                    (kitchensink/mapvals sutils/parse-db-json [:edges :resources] %))
                       (http/status-not-found-response "catalog" node)
                       (select-keys puppetdb-query global-engine-params)))))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for the node in route-params"
  [version]
  (fn [{:keys [globals puppetdb-query route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "factsets" ["=" "certname" node]]
                       (http-q/narrow-globals globals)
                       identity
                       (http/status-not-found-response "factset" node)
                       (select-keys puppetdb-query global-engine-params)))))

(defn node-status
  "Produce a response body for a single environment."
  [version]
  (fn [{:keys [globals puppetdb-query route-params]}]
    (let [node (:node route-params)]
      (status-response version
                       ["from" "nodes" ["=" "certname" node]]
                       (http-q/narrow-globals globals)
                       identity
                       (http/status-not-found-response "node" node)
                       (select-keys puppetdb-query global-engine-params)))))

(defn environment-status
  "Produce a response body for a single environment."
  [version]
  (fn [{:keys [globals puppetdb-query route-params]}]
    (let [environment (:environment route-params)]
      (status-response version
                       ["from" "environments" ["=" "name" environment]]
                       (http-q/narrow-globals globals)
                       identity
                       (http/status-not-found-response "environment" environment)
                       (select-keys puppetdb-query global-engine-params)))))

(defn producer-status
  "Produce a response body for a single producer."
  [version]
  (fn [{:keys [globals puppetdb-query route-params]}]
    (let [producer (:producer route-params)]
      (status-response version
                       ["from" "producers" ["=" "name" producer]]
                       (http-q/narrow-globals globals)
                       identity
                       (http/status-not-found-response "producer" producer)
                       (select-keys puppetdb-query global-engine-params)))))

;; Routes

;; query parameter sets
(def global-params {:optional ["optimize_drop_unused_joins"
                               "include_facts_expiration"
                               "include_package_inventory"
                               "explain"]})
(def paging-params {:optional paging/query-params})
(def pretty-params {:optional ["pretty"]})
(def typical-params (merge-param-specs global-params
                                       paging-params
                                       pretty-params
                                       {:optional ["include_package_inventory"]}))

(pls/defn-validated root-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (let [no-certname-entities #{"fact_paths" "environments" "packages"}
        get-entity #(some-> % :puppetdb-query :query second)]
    (cmdi/ANY "" []
              (-> (comp (http-q/query-handler version)
                        (fn [req]
                          (cond
                            (some-> req :puppetdb-query :ast_only http-q/coerce-to-boolean) req
                            (no-certname-entities (get-entity req)) req
                            :else (http-q/restrict-query-to-active-nodes req))))
                  (http-q/extract-query-pql
                   (merge-param-specs typical-params
                                      {:optional ["ast_only"]
                                       :required ["query"]}))))))

(pls/defn-validated events-routes :- bidi-schema/RoutePair
  "Ring app for querying events"
  [version :- s/Keyword]
  (extract-query
   (merge-param-specs typical-params
                      {:optional ["query"
                                  "distinct_resources"
                                  "distinct_start_time"
                                  "distinct_end_time"]})
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "events")))))

(pls/defn-validated reports-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (cmdi/routes
   (extract-query
    typical-params
    (cmdi/ANY "" []
              (create-query-handler version "reports")))

   (cmdi/context ["/" :hash "/events"]
                 (-> (events-routes version)
                     (append-handler (comp http-q/restrict-query-to-report))
                     (wrap-with-parent-check version :report :hash)))

   (cmdi/ANY ["/" :hash "/metrics"] []
             (-> (report-data-responder version "report_metrics")
                 (parent-check version :report :hash)
                 (validate-query-params (merge-param-specs global-params
                                                           pretty-params))))

   (cmdi/ANY ["/" :hash "/logs"] []
             (-> (report-data-responder version "report_logs")
                 (parent-check version :report :hash)
                 (validate-query-params (merge-param-specs global-params
                                                           pretty-params))))))

(pls/defn-validated resources-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "resources"  http-q/restrict-query-to-active-nodes))

    (cmdi/context ["/" (route-param :type)]
                  (cmdi/ANY "" []
                            (create-query-handler version "resources"
                                            http-q/restrict-resource-query-to-type
                                            http-q/restrict-query-to-active-nodes))
                  (cmdi/ANY ["/" (route-param :title)] []
                            (create-query-handler version "resources"
                                            http-q/restrict-resource-query-to-title
                                            http-q/restrict-resource-query-to-type
                                            http-q/restrict-query-to-active-nodes))))))

(pls/defn-validated edge-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/ANY "" []
             (create-query-handler version "edges" http-q/restrict-query-to-active-nodes))))

(pls/defn-validated catalog-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes

    (cmdi/ANY "" []
              (create-query-handler version "catalogs"))

    (cmdi/context ["/" (route-param :node)]
                  (cmdi/ANY "" []
                            (catalog-status version))

                  (cmdi/context "/edges"
                                (-> (edge-routes version)
                                    (wrap-with-parent-check version :catalog :node)))

                  (cmdi/context "/resources"
                                (-> (resources-routes version)
                                    (append-handler http-q/restrict-query-to-node)
                                    (wrap-with-parent-check version :catalog :node)))))))

(pls/defn-validated catalog-input-contents-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "catalog-input-contents"
                                    http-q/restrict-query-to-active-nodes)))))

(pls/defn-validated catalog-inputs-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "catalog-inputs"
                                    http-q/restrict-query-to-active-nodes)))))


(pls/defn-validated facts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "facts" http-q/restrict-query-to-active-nodes))

    (cmdi/context ["/" (route-param :fact)]

                  (cmdi/ANY "" []
                            (create-query-handler version "facts"
                                            http-q/restrict-fact-query-to-name
                                            http-q/restrict-query-to-active-nodes))

                  (cmdi/ANY ["/" (route-param :value)] []
                            (create-query-handler version "facts"
                                            http-q/restrict-fact-query-to-name
                                            http-q/restrict-fact-query-to-value
                                            http-q/restrict-query-to-active-nodes))))))

(pls/defn-validated inventory-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
    typical-params
    (cmdi/routes
      (cmdi/ANY "" []
                (create-query-handler version "inventory"
                                      http-q/restrict-query-to-active-nodes)))))

(pls/defn-validated packages-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "packages")))))

(pls/defn-validated package-inventory-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
    typical-params
    (cmdi/routes
      (cmdi/ANY "" []
                (create-query-handler version "package_inventory"
                                      http-q/restrict-query-to-active-nodes))
      (cmdi/context ["/" :node]
                    (cmdi/ANY "" [] (create-query-handler version "package_inventory"
                                                          http-q/restrict-query-to-node))))))

(pls/defn-validated factset-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "factsets" http-q/restrict-query-to-active-nodes))

    (cmdi/context ["/" :node]
                  (cmdi/ANY "" []
                            (factset-status version))

                  (cmdi/ANY "/facts" []
                            (-> (create-query-handler version "factsets" http-q/restrict-query-to-node)
                                (parent-check version :factset :node)))))))

(pls/defn-validated fact-names-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/ANY "" []
             (comp
              (fn [{:keys [params globals puppetdb-query]}]
                (let [puppetdb-query (assoc-when puppetdb-query :order_by [[:name :ascending]])]
                  (produce-streaming-body
                   version
                   (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                   (http-q/narrow-globals globals))))
              (http-q/restrict-query-to-entity "fact_names")))))

(pls/defn-validated node-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/routes
    (cmdi/ANY "" []
              (create-query-handler version "nodes" http-q/restrict-query-to-active-nodes))
    (cmdi/context ["/" (route-param :node)]
                  (cmdi/ANY "" [] (node-status version))
                  (cmdi/context "/facts"
                                (-> (facts-routes version)
                                    (append-handler http-q/restrict-query-to-node)
                                    (wrap-with-parent-check version :node :node)))
                  (cmdi/context "/resources"
                                (-> (resources-routes version)
                                    (append-handler http-q/restrict-query-to-node)
                                    (wrap-with-parent-check version :node :node)))))))

(pls/defn-validated environments-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (cmdi/routes
   (extract-query
    typical-params
    (cmdi/ANY "" []
              (create-query-handler version "environments")))
   (cmdi/context ["/" (route-param :environment)]
                 (cmdi/ANY "" []
                   (validate-query-params (environment-status version)
                                          (merge-param-specs global-params
                                                             pretty-params)))

                 (wrap-with-parent-check
                  (cmdi/routes
                   (extract-query
                    typical-params
                    (cmdi/context "/facts"
                                  (-> (facts-routes version)
                                      (append-handler http-q/restrict-query-to-environment))))

                   (extract-query
                    typical-params
                    (cmdi/context "/resources"
                                  (-> (resources-routes version)
                                      (append-handler http-q/restrict-query-to-environment))))

                   (extract-query
                    typical-params
                    (cmdi/context "/reports"
                                  (-> (reports-routes version)
                                      (append-handler http-q/restrict-query-to-environment))))

                   (extract-query
                    (merge-param-specs
                     typical-params
                     {:optional ["query"
                                 "distinct_resources"
                                 "distinct_start_time"
                                 "distinct_end_time"]})

                    (cmdi/context "/events"
                                  (-> (events-routes version)
                                      (append-handler http-q/restrict-query-to-environment)))))
                  version :environment :environment))))

(pls/defn-validated producers-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (cmdi/routes
   (extract-query
    typical-params
    (cmdi/ANY "" []
              (create-query-handler version "producers")))
   (cmdi/context ["/" (route-param :producer)]
                 (cmdi/ANY "" []
                   (validate-query-params (producer-status version)
                                          (merge-param-specs global-params
                                                             pretty-params)))
                 (wrap-with-parent-check
                  (cmdi/routes
                   (extract-query
                    typical-params
                    (cmdi/context "/factsets"
                                  (-> (factset-routes version)
                                      (append-handler http-q/restrict-query-to-producer))))

                  (extract-query
                    typical-params
                    (cmdi/context "/catalogs"
                                  (-> (catalog-routes version)
                                      (append-handler http-q/restrict-query-to-producer))))

                   (extract-query
                    typical-params
                    (cmdi/context "/reports"
                                  (-> (reports-routes version)
                                      (append-handler http-q/restrict-query-to-producer)))))
                  version :producer :producer))))

(pls/defn-validated fact-contents-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/ANY "" []
             (create-query-handler version "fact_contents" http-q/restrict-query-to-active-nodes))))

(pls/defn-validated fact-path-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   typical-params
   (cmdi/ANY "" []
             (create-query-handler version "fact_paths"))))

(pls/defn-validated event-counts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   (merge-param-specs typical-params
                      {:required ["summarize_by"]
                       :optional ["counts_filter" "count_by"
                                  "distinct_resources" "distinct_start_time"
                                  "distinct_end_time"]})
   (cmdi/ANY "" []
             (create-query-handler version "event_counts"))))

(pls/defn-validated agg-event-counts-routes :- bidi-schema/RoutePair
  [version :- s/Keyword]
  (extract-query
   (merge-param-specs global-params
                      {:required ["summarize_by"]
                       :optional ["query" "counts_filter" "count_by"
                                  "distinct_resources" "distinct_start_time"
                                  "distinct_end_time"]})
   (cmdi/ANY "" []
             (create-query-handler version
                                   "aggregate_event_counts"))))
