(ns puppetlabs.puppetdb.http.v4
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.index :as index]
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

(defn create-query-handler [entity param-spec]
  (comp (http-q/query-handler version)
        #(http-q/restrict-query-to-entity entity %)
        (http-q/extract-query' param-spec)))

(defn create-paging-query-handler [entity]
  (comp (http-q/query-handler version)
        #(http-q/restrict-query-to-entity entity %)
        (http-q/extract-query' {:optional paging/query-params})))

(defn experimental-index-app
  [version]
  (bring/wrap-middleware (index/index-app version)
                         (fn [app]
                           (partial http/experimental-warning app  "The root endpoint is experimental"))))

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
  (comp (http-q/query-handler version)
        #(http-q/restrict-query-to-entity "events" %)
        (http-q/extract-query' {:optional (concat
                               ["query"
                                "distinct_resources"
                                "distinct_start_time"
                                "distinct_end_time"]
                               paging/query-params)})))



(defn reports-app
  [version]
  {"" [["" (create-paging-query-handler "reports")]
       
       [["/" :hash "/events"]
        (wrap-with-parent-check'' (comp (events-app version) http-q/restrict-query-to-report') version :report :hash)]
       
       [["/" :hash "/metrics"]
        (-> (report-data-responder version "report_metrics")
            validate-no-query-params
            (wrap-with-parent-check'' version :report :hash))]
       
       [["/" :hash "/logs"]
        (-> (report-data-responder version "report_logs")
            validate-no-query-params
            (wrap-with-parent-check'' version :report :hash))]]})

(defn url-decode [x]
  (java.net.URLDecoder/decode x))

(defn resources-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" (comp (http-q/query-handler version)
              #(http-q/restrict-query-to-entity "resources" %)
              http-q/restrict-query-to-active-nodes
              (http-q/extract-query' param-spec))

     ["/" :type]
     {"" (comp (http-q/query-handler version)
               #(http-q/restrict-query-to-entity "resources" %)
               (fn [{:keys [route-params] :as req}]
                 (http-q/restrict-resource-query-to-type (:type route-params) req))
               http-q/restrict-query-to-active-nodes
               (http-q/extract-query' param-spec))
      
      ["/" [#".*" :title]]
      (comp (http-q/query-handler version)
            #(http-q/restrict-query-to-entity "resources" %)
            (fn [{:keys [route-params] :as req}]
              (http-q/restrict-resource-query-to-title (url-decode (:title route-params)) req))
            (fn [{:keys [route-params] :as req}]
              (http-q/restrict-resource-query-to-type (:type route-params) req))
            http-q/restrict-query-to-active-nodes
            (http-q/extract-query' param-spec))}}))

(defn catalog-status
  "Produce a response body for a request to retrieve the catalog for `node`."
  [api-version node options]
  (let [catalog (first
                 (stream-query-result api-version
                                          ["from" "catalogs" ["=" "certname" node]]
                                          {}
                                          options))]
    (if catalog
      (http/json-response (s/validate catalog-query-schema
                                      (kitchensink/mapvals sutils/parse-db-json [:edges :resources] catalog)))
      (http/status-not-found-response "catalog" node))))

(defn catalog-app
  [version]
  {"" (create-paging-query-handler "catalogs")

   ["/" :node]
   {"" (fn [{:keys [globals route-params]}]
         (catalog-status version (:node route-params)
                         (select-keys globals [:scf-read-db :url-prefix :warn-experimental])))

    ["/edges"]
    (-> (comp (http-q/query-handler version)
              http-q/restrict-query-to-node'
              #(http-q/restrict-query-to-entity "edges"  %)
              (http-q/extract-query' {:optional paging/query-params}))
        (wrap-with-parent-check'' version :catalog :node))

    ["/resources"]
    (-> (comp (resources-app version)
              http-q/restrict-query-to-node')
        (wrap-with-parent-check'' version :catalog :node))}})

(defn facts-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {""
     (comp (http-q/query-handler version)
           #(http-q/restrict-query-to-entity "facts" %)
           http-q/restrict-query-to-active-nodes
           (http-q/extract-query' param-spec))

     ["/" :fact]
     {"" (comp (http-q/query-handler version)
               #(http-q/restrict-query-to-entity "facts" %)
               (fn [{:keys [route-params] :as req}]
                 (http-q/restrict-fact-query-to-name (:fact route-params) req))
               http-q/restrict-query-to-active-nodes
               (http-q/extract-query' param-spec))

      ["/" :value]
      (comp (http-q/query-handler version)
            #(http-q/restrict-query-to-entity "facts" %)
            (fn [{:keys [route-params] :as req}]
              (http-q/restrict-fact-query-to-name (:fact route-params) req))
            (fn [{:keys [route-params] :as req}]
              (http-q/restrict-fact-query-to-value (:value route-params) req))
            http-q/restrict-query-to-active-nodes
            (http-q/extract-query' param-spec))}}))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for `node`."
  [api-version node options]
  (let [factset (first
                 (stream-query-result api-version
                                          ["from" "factsets" ["=" "certname" node]]
                                          {}
                                          options))]
    (if factset
      (http/json-response factset)
      (http/status-not-found-response "factset" node))))

(defn factset-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" 
     (comp (http-q/query-handler version)
           #(http-q/restrict-query-to-entity "factsets" %)
           http-q/restrict-query-to-active-nodes
           (http-q/extract-query' param-spec))

     ["/" :node]
     {"" (fn [{:keys [globals route-params]}]
           (factset-status version (:node route-params)
                           (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))

      ["/facts"]
      (wrap-with-parent-check'' (comp (http-q/query-handler version)
                                      #(http-q/restrict-query-to-entity "factsets" %)
                                      http-q/restrict-query-to-node'
                                      (http-q/extract-query' param-spec))
                                version :factset :node)}}))

(defn fact-names-app
  [version]
  (http-q/extract-query (comp
                         (fn [{:keys [params globals puppetdb-query]}]
                           (let [puppetdb-query (assoc-when puppetdb-query :order_by [[:name :ascending]])]
                             (produce-streaming-body
                              version
                              (http-q/validate-distinct-options! (merge (keywordize-keys params) puppetdb-query))
                              (select-keys globals [:scf-read-db :url-prefix :pretty-print :warn-experimental]))))
                         (partial http-q/restrict-query-to-entity "fact_names"))
                        {:optional paging/query-params}))

(defn node-status
  "Produce a response body for a single environment."
  [api-version node options]
  (let [status (first
                (stream-query-result api-version
                                         ["from" "nodes" ["=" "certname" node]]
                                         {}
                                         options))]
    (if status
      (http/json-response status)
      (http/status-not-found-response "node" node))))

(defn node-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" (comp (http-q/query-handler version)
              #(http-q/restrict-query-to-entity "nodes" %)
              http-q/restrict-query-to-active-nodes
              (http-q/extract-query' param-spec))

     ["/" :node]
     {"" (-> (fn [{:keys [globals route-params]}]
               (node-status version
                            (:node route-params)
                            (select-keys globals [:scf-read-db :url-prefix :warn-experimental])))
             ;; Being a singular item, querying and pagination don't really make
             ;; sense here
             (validate-query-params {})) 

      ["/facts"]
      (second
       (cmdi/wrap-routes 
        (cmdi/wrap-routes ["" (facts-app version)]
                          (fn [handler]
                            (comp handler
                                  http-q/restrict-query-to-node'
                                  (http-q/extract-query' param-spec))))
        #(wrap-with-parent-check'' % version :node :node)))

      ["/resources"]
      (second
       (cmdi/wrap-routes 
        (cmdi/wrap-routes ["" (resources-app version)]
                          (fn [handler]
                            (comp handler
                                  http-q/restrict-query-to-node'
                                  (http-q/extract-query' param-spec))))
        #(wrap-with-parent-check'' % version :node :node)))}}))

(defn environment-status
  "Produce a response body for a single environment."
  [api-version environment options]
  (let [status (first
                (stream-query-result api-version
                                         ["from" "environments" ["=" "name" environment]]
                                         {}
                                         options))]
    (if status
      (http/json-response status)
      (http/status-not-found-response "environment" environment))))

(defn environments-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    {"" (http-q/query-route-from' "environments" version param-spec)

     ["/" :environment]
     {"" (validate-query-params (fn [{:keys [globals route-params]}]
                                  (environment-status version (:environment route-params)
                                                      (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))
                                {})

      ["/facts"]
      (second
       (cmdi/wrap-routes ["" (facts-app version)]
                         (fn [handler]
                           (wrap-with-parent-check''
                            (comp handler
                                  http-q/restrict-query-to-environment'
                                  (http-q/extract-query' param-spec))
                            version :environment :environment))))
      

      ["/resources"]
      (second
       (cmdi/wrap-routes ["" (resources-app version)]
                         (fn [handler]
                           (wrap-with-parent-check''
                            (comp handler
                                  http-q/restrict-query-to-environment'
                                  (http-q/extract-query' param-spec))
                            version :environment :environment))))


      ["/events"]
      (second
       (cmdi/wrap-routes ["" (events-app version)]
                         (fn [handler]
                           (wrap-with-parent-check''
                            (comp handler
                                  http-q/restrict-query-to-environment'
                                  (http-q/extract-query' {:optional (concat
                                                                     ["query"
                                                                      "distinct_resources"
                                                                      "distinct_start_time"
                                                                      "distinct_end_time"]
                                                                     paging/query-params)}))
                            version :environment :environment))))
      

      ["/reports"]
      (second
       (cmdi/wrap-routes ["" (reports-app version)]
                         (fn [handler]
                           (wrap-with-parent-check'' (comp handler
                                                           http-q/restrict-query-to-environment'
                                                           (http-q/extract-query' param-spec))
                                                     version :environment :environment))))}}))

(def v4-app
  {"" (experimental-index-app version)
   "/facts" (facts-app version)
   "/edges" (comp (http-q/query-handler version)
                  http-q/restrict-query-to-active-nodes
                  #(http-q/restrict-query-to-entity "edges" %)
                  (http-q/extract-query' {:optional paging/query-params}))
   "/factsets" (factset-app version)
   "/fact-names" (fact-names-app version)
   "/fact-contents"   (comp (http-q/query-handler version)
                            #(http-q/restrict-query-to-entity "fact_contents" %)
                            http-q/restrict-query-to-active-nodes
                            (http-q/extract-query' {:optional paging/query-params}))
   "/fact-paths" (create-paging-query-handler "fact_paths")
   
   "/nodes" (node-app version)
   "/environments" (environments-app version)


   "/resources" (resources-app version)
   "/catalogs" (catalog-app version)
   "/events" (events-app version)
   "/event-counts" (create-query-handler "event_counts" {:required ["summarize_by"]
                                                         :optional (concat ["counts_filter" "count_by"
                                                                            "distinct_resources" "distinct_start_time"
                                                                            "distinct_end_time"]
                                                                           paging/query-params)})
   "/aggregate-event-counts" (create-query-handler "aggregate_event_counts"
                                                   {:required ["summarize_by"]
                                                    :optional ["query" "counts_filter" "count_by"
                                                               "distinct_resources" "distinct_start_time"
                                                               "distinct_end_time"]}) 
   "/reports" (reports-app version)})
