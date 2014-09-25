(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.query.aggregate-event-counts :as aggregate-event-counts]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :as events-http]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params]]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals]}]
           (let [{:strs [query summarize-by counts-filter count-by] :as query-params} params
                 counts-filter (if counts-filter (json/parse-string counts-filter true))
                 distinct-options (events-http/validate-distinct-options! query-params)
                 query-options (merge {:counts-filter counts-filter :count-by count-by} distinct-options)]
             (produce-streaming-body
              :aggregate-event-counts
              version
              query
              [summarize-by query-options]
              (:scf-read-db globals))))}))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional ["counts-filter" "count-by" "distinct-resources"
                                         "distinct-start-time" "distinct-end-time"]})))
