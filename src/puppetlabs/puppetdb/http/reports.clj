(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.events :as e]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    validate-query-params
                                                    validate-no-query-params
                                                    wrap-with-paging-options
                                                    wrap-with-parent-check]]))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity hash]
  (app
    []
    (fn [{:keys [globals]}]
      (let [{db :scf-read-db url-prefix :url-prefix} globals
            query (json/generate-string ["=" "hash" hash])]
        (produce-streaming-body entity version {:query query} db url-prefix)))))

(defn routes
  [version optional-handlers]
  (let [handlers (or optional-handlers [identity])
        param-spec {:optional (cons "query" paging/query-params)}
        query-route #(apply (partial http-q/query-route :reports version param-spec) %)]
    (app
      []
      (query-route handlers)

      [hash "events" &]
      (-> (e/events-app version (partial http-q/restrict-query-to-report hash))
          (wrap-with-parent-check version :report hash))

      [hash "metrics" &]
      (-> (report-data-responder version :report-metrics hash)
          validate-no-query-params
          verify-accepts-json
          (wrap-with-parent-check version :report hash))

      [hash "logs" &]
      (-> (report-data-responder version :report-logs hash)
          validate-no-query-params
          verify-accepts-json
          (wrap-with-parent-check version :report hash)))))

(defn reports-app
  [version & optional-handlers]
  (-> (routes version optional-handlers)
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
