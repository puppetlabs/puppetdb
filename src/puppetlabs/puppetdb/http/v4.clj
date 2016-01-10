(ns puppetlabs.puppetdb.http.v4
  (:require [puppetlabs.puppetdb.http :as http]
            [bidi.bidi :as bidi]
            [bidi.ring :as bring]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body
                                                   stream-query-result]]
            [puppetlabs.puppetdb.middleware :refer [validate-no-query-params
                                                    validate-query-params
                                                    wrap-with-parent-check
                                                    wrap-with-parent-check'
                                                    wrap-with-parent-check'']]
            [puppetlabs.comidi :as cmdi]
            [schema.core :as s]
            [puppetlabs.puppetdb.catalogs :refer [catalog-query-schema]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.puppetdb.utils :refer [assoc-when]]
            [clojure.walk :refer [keywordize-keys]]))

(def version :v4)

(defn create-handler
  [version entity param-spec & handler-fns]
  (apply comp
         (http-q/query-handler version)
         #(http-q/restrict-query-to-entity entity %)
         (concat handler-fns
                 [(http-q/extract-query' param-spec)])))

(defn add-parent-check [route version entity]
  (cmdi/wrap-routes route
                    #(wrap-with-parent-check'' % version entity entity)))

(defn experimental-index-app
  [version]
  (cmdi/wrap-routes (cmdi/ANY "" []
                              (comp (http-q/query-handler version)
                                    (http-q/extract-query' {:optional paging/query-params
                                                            :required ["query"]})))
                    (fn [handler]
                      (fn [req]
                        (http/experimental-warning handler "The root endpoint is experimental" req)))))



(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity]
  (fn [{:keys [globals route-params] :as foo}]
    (let [query ["from" entity ["=" "hash" (:hash route-params)]]]
      (produce-streaming-body version {:query query}
                              (select-keys globals [:scf-read-db
                                                    :url-prefix
                                                    :pretty-print
                                                    :warn-experimental])))))

(defn events-app
  "Ring app for querying events"
  [version]
  (cmdi/ANY "" []
            (create-handler version "events" {:optional (concat
                                                         ["query"
                                                          "distinct_resources"
                                                          "distinct_start_time"
                                                          "distinct_end_time"]
                                                         paging/query-params)})))

(defn reports-app
  [version]
  (cmdi/routes
   (cmdi/ANY "" []
             (create-handler version "reports" {:optional paging/query-params}))
   (cmdi/ANY ["/" :hash "/events"] []
             (wrap-with-parent-check'' (comp (events-app version) http-q/restrict-query-to-report') version :report :hash))
       
   (cmdi/ANY ["/" :hash "/metrics"] []
             (-> (report-data-responder version "report_metrics")
                 validate-no-query-params
                 (wrap-with-parent-check'' version :report :hash)))
       
   (cmdi/ANY ["/" :hash "/logs"] []
             (-> (report-data-responder version "report_logs")
                 validate-no-query-params
                 (wrap-with-parent-check'' version :report :hash)))))

(defn url-decode [x]
  (java.net.URLDecoder/decode x))

(defn resources-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (cmdi/ANY "" []
               (create-handler version "resources" param-spec http-q/restrict-query-to-active-nodes))
     
     (cmdi/context ["/" :type]
                   (cmdi/ANY "" []
                             (create-handler version "resources" param-spec
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-resource-query-to-type (:type route-params) req))
                                             http-q/restrict-query-to-active-nodes))
                   (cmdi/ANY ["/" [#".*" :title]] []
                             (create-handler version "resources" param-spec
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-resource-query-to-title (url-decode (:title route-params)) req))
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-resource-query-to-type (:type route-params) req))
                                             http-q/restrict-query-to-active-nodes))))))

(defn status-handler [version query options found-fn not-found-response]
  (if-let [catalog (first (stream-query-result version query {} options))]
    (http/json-response (found-fn catalog))
    not-found-response))

(defn catalog-status [version node options]
  (status-handler version ["from" "catalogs" ["=" "certname" node]] options
                  #(s/validate catalog-query-schema
                               (kitchensink/mapvals sutils/parse-db-json [:edges :resources] %))
                  (http/status-not-found-response "catalog" node)))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for `node`."
  [api-version node options]
  (status-handler version ["from" "factsets" ["=" "certname" node]] options
                  identity
                  (http/status-not-found-response "factset" node)))

(defn node-status
  "Produce a response body for a single environment."
  [api-version node options]
  (status-handler version ["from" "nodes" ["=" "certname" node]] options
                  identity
                  (http/status-not-found-response "node" node)))

(defn environment-status
  "Produce a response body for a single environment."
  [api-version environment options]
  (status-handler version ["from" "environments" ["=" "name" environment]] options
                  identity
                  (http/status-not-found-response "environment" environment)))

(defn catalog-app
  [version]
  (cmdi/routes

   (cmdi/ANY "" []
             (create-handler version "catalogs" {:optional paging/query-params}))

   (cmdi/context ["/" :node]
                 (cmdi/ANY "" []
                           (fn [{:keys [globals route-params]}]
                             (catalog-status version (:node route-params)
                                             (select-keys globals [:scf-read-db :url-prefix :warn-experimental]))))

                 (cmdi/ANY "/edges" []
                           (-> (create-handler version "edges" paging/query-params http-q/restrict-query-to-node')
                               (wrap-with-parent-check'' version :catalog :node)))
                 
                 (cmdi/ANY "/resources" []
                           (-> (comp (resources-app version)
                                     http-q/restrict-query-to-node')
                               (wrap-with-parent-check'' version :catalog :node))))))

(defn facts-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (cmdi/ANY "" []
               (create-handler version "facts" param-spec http-q/restrict-query-to-active-nodes))

     (cmdi/context ["/" :fact]
                   
                   (cmdi/ANY "" []
                             (create-handler version "facts" param-spec
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-fact-query-to-name (:fact route-params) req))
                                             http-q/restrict-query-to-active-nodes))

                   (cmdi/ANY ["/" :value] []
                             (create-handler version "facts" param-spec
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-fact-query-to-name (:fact route-params) req))
                                             (fn [{:keys [route-params] :as req}]
                                               (http-q/restrict-fact-query-to-value (:value route-params) req))
                                             http-q/restrict-query-to-active-nodes))))))

(defn factset-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (cmdi/ANY "" []
               (create-handler version "factsets" param-spec http-q/restrict-query-to-active-nodes))

     (cmdi/context ["/" :node]
                   (cmdi/ANY "" []
                             (fn [{:keys [globals route-params]}]
                               (factset-status version (:node route-params)
                                               (select-keys globals [:scf-read-db :warn-experimental :url-prefix]))))

                   (cmdi/ANY "/facts" []
                             (-> (create-handler version "factsets" param-spec http-q/restrict-query-to-node')
                                 (wrap-with-parent-check'' version :factset :node)))))))

(defn fact-names-app
  [version]
  (cmdi/ANY "" []
   (http-q/extract-query (comp
                          (fn [{:keys [params globals puppetdb-query]}]
                            (let [puppetdb-query (assoc-when puppetdb-query :order_by [[:name :ascending]])]
                              (produce-streaming-body
                               version
                               (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                               (select-keys globals [:scf-read-db :url-prefix :pretty-print :warn-experimental]))))
                          (partial http-q/restrict-query-to-entity "fact_names"))
                         {:optional paging/query-params})))

(defn node-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (cmdi/ANY "" []
               (create-handler version "nodes" param-spec http-q/restrict-query-to-active-nodes))
     (cmdi/context ["/" :node]
                   (cmdi/ANY "" []
                             (-> (fn [{:keys [globals route-params]}]
                                   (node-status version
                                                (:node route-params)
                                                (select-keys globals [:scf-read-db :url-prefix :warn-experimental])))
                                 ;; Being a singular item, querying and pagination don't really make
                                 ;; sense here
                                 (validate-query-params {})))
                   (cmdi/context "/facts"
                             (cmdi/wrap-routes
                              (cmdi/wrap-routes (facts-app version)
                                            (fn [handler]
                                              (comp handler
                                                    http-q/restrict-query-to-node'
                                                    (http-q/extract-query' param-spec))))
                              #(wrap-with-parent-check'' % version :node :node)))
                   (cmdi/context "/resources"
                             (cmdi/wrap-routes
                              (cmdi/wrap-routes (resources-app version)
                                            (fn [handler]
                                              (comp handler
                                                    http-q/restrict-query-to-node'
                                                    (http-q/extract-query' param-spec))))
                              #(wrap-with-parent-check'' % version :node :node)))))))

(defn environments-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    (cmdi/routes
     (cmdi/ANY "" []
               (create-handler version "environments" param-spec))
     (cmdi/context ["/" :environment]
                   (cmdi/ANY "" []
                             (validate-query-params (fn [{:keys [globals route-params]}]
                                                      (environment-status version (:environment route-params)
                                                                          (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))
                                                    {}))
                   (add-parent-check
                    (cmdi/routes
                     (cmdi/context "/facts"
                                   (cmdi/wrap-routes (facts-app version)
                                                     (fn [handler]
                                                       (comp handler
                                                             http-q/restrict-query-to-environment'
                                                             (http-q/extract-query' param-spec)))))
                     (cmdi/context "/resources"
                                   (cmdi/wrap-routes (resources-app version)
                                                     (fn [handler]
                                                       (comp handler
                                                             http-q/restrict-query-to-environment'
                                                             (http-q/extract-query' param-spec)))))


                     (cmdi/context "/events"
                                   
                                   (cmdi/wrap-routes (events-app version)
                                                     (fn [handler]
                                                       (comp handler
                                                             http-q/restrict-query-to-environment'
                                                             (http-q/extract-query' {:optional (concat
                                                                                                ["query"
                                                                                                 "distinct_resources"
                                                                                                 "distinct_start_time"
                                                                                                 "distinct_end_time"]
                                                                                                paging/query-params)})))))
                     

                     (cmdi/context "/reports"
                                   (cmdi/wrap-routes (reports-app version)
                                                     (fn [handler]
                                                       (comp handler
                                                             http-q/restrict-query-to-environment'
                                                             (http-q/extract-query' param-spec))))))
                    version :environment)))))

(def v4-app
  (cmdi/routes
   (cmdi/context ""
                 (experimental-index-app version))
   (cmdi/context "/facts" (facts-app version))
   (cmdi/context "/edges"
                 (cmdi/ANY "" []
                           (create-handler version "edges" {:optional paging/query-params} http-q/restrict-query-to-active-nodes)))
   (cmdi/context "/factsets"
                 (factset-app version))
   (cmdi/context "/fact-names" (fact-names-app version))
   (cmdi/context "/fact-contents"   (cmdi/ANY "" []
                                              (create-handler version "fact_contents" {:optional paging/query-params} http-q/restrict-query-to-active-nodes)))
   (cmdi/context "/fact-paths"
                 (cmdi/ANY "" []
                           (create-handler version
                                           "fact_paths"
                                           {:optional paging/query-params})))
   
   (cmdi/context "/nodes" (node-app version))
   (cmdi/context "/environments" (environments-app version))


   (cmdi/context "/resources" (resources-app version))
   (cmdi/context "/catalogs" (catalog-app version))
   (cmdi/context "/events" (events-app version))
   (cmdi/context "/event-counts"
                 (cmdi/ANY "" []
                           (create-handler version
                                           "event_counts"
                                           {:required ["summarize_by"]
                                            :optional (concat ["counts_filter" "count_by"
                                                               "distinct_resources" "distinct_start_time"
                                                               "distinct_end_time"]
                                                              paging/query-params)})))
   (cmdi/context "/aggregate-event-counts"
                 (cmdi/ANY "" []
                           (create-handler version
                                           "aggregate_event_counts"
                                           {:required ["summarize_by"]
                                            :optional ["query" "counts_filter" "count_by"
                                                       "distinct_resources" "distinct_start_time"
                                                       "distinct_end_time"]}))) 
   (cmdi/context "/reports" (reports-app version))))
