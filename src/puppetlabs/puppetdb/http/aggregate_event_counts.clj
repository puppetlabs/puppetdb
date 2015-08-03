(ns puppetlabs.puppetdb.http.aggregate-event-counts
  (:require [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.http.events :refer [validate-distinct-options!]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params]]
            [clojure.tools.logging :as log]
            [net.cgrand.moustache :refer [app]]))

(defn routes
  [version]
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (when (= "puppetdb" (:product-name globals))
              (log/warn "The aggregate-event-counts endpoint is experimental"
                        " and may be altered or removed in the future."))
            (if (utils/hsql? (:scf-read-db globals))
              (http/json-response
                {:error "The aggregate-event-counts endpoint does not support HSQLDB."}
                http/status-not-implemented)
              (let [{:strs [query summarize_by counts_filter count_by]} params
                    distinct-options (validate-distinct-options! params)
                    counts_filter' (json/parse-string counts_filter true)
                    query-options (-> {:counts_filter counts_filter'
                                       :count_by count_by}
                                      (merge distinct-options))]
                (log/warn
                  (str "The aggregate-event-counts endpoint is experimental"
                       " and may be altered or removed in the future."))
                (produce-streaming-body
                  :aggregate-event-counts
                  version
                  query
                  [summarize_by query-options]
                  (:scf-read-db globals)
                  (:url-prefix globals)))))}))

(defn aggregate-event-counts-app
  "Ring app for querying for aggregated summary information about resource events."
  [version]
  (-> (routes version)
      verify-accepts-json
      (validate-query-params {:required ["summarize_by"]
                              :optional ["query"
                                         "counts_filter" "count_by" "distinct_resources"
                                         "distinct_start_time" "distinct_end_time"]})))
