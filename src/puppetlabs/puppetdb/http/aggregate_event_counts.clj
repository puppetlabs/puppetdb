(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :as events-http]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params]]
            [clojure.tools.logging :as log]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [globals]
  (let [{:keys [api-version url-prefix scf-read-db]} globals]
    (app
     [""]
     {:get (fn [{:keys [params]}]
             (let [{:strs [query summarize_by counts_filter count_by] :as query-params} params
                   distinct-options (events-http/validate-distinct-options! query-params)
                   query-options (-> {:counts_filter (when counts_filter (json/parse-string counts_filter true))
                                      :count_by count_by}
                                     (merge distinct-options))]
               (produce-streaming-body
                :aggregate-event-counts
                api-version
                query
                [summarize_by query-options]
                scf-read-db
                url-prefix)))})))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [globals]
  (log/warn "The aggregate-event-counts endpoint is experimental and may be altered or removed in the future.")
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize_by"]
                              :optional ["counts_filter" "count_by" "distinct_resources"
                                         "distinct_start_time" "distinct_end_time"]})))
