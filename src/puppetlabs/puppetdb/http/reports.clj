(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.events :as e]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :refer [validate-no-query-params
                                                    wrap-with-parent-check
                                                    wrap-with-parent-check']]
            [bidi.ring :as bring]))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity]
  {"" (fn [{:keys [globals route-params]}]
        (let [query ["from" entity ["=" "hash" (:hash route-params)]]]
          (produce-streaming-body version {:query query}
                                  (select-keys globals [:scf-read-db
                                                        :url-prefix
                                                        :pretty-print
                                                        :warn-experimental]))))})

(defn reports-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    {"" (http-q/query-route-from' "reports" version param-spec optional-handlers)
     ["/" :hash "/events"]
     (bring/wrap-middleware (e/events-app version http-q/restrict-query-to-report')
                            (fn [app]
                              (fn [req]
                                ((wrap-with-parent-check app version :report (get-in req [:route-params :hash]))
                                 req))))
     ["/" :hash "/metrics"]
     (bring/wrap-middleware (bring/wrap-middleware (report-data-responder version "report_metrics")
                                                   validate-no-query-params)
                            (fn [app]
                              (fn [req]
                                ((wrap-with-parent-check app version :report (get-in req [:route-params :hash]))
                                 req))))
     ["/" :hash "/logs"]
     (bring/wrap-middleware
      (bring/wrap-middleware (report-data-responder version "report_logs")
                             validate-no-query-params)
      (fn [app]
        (fn [req]
          ((wrap-with-parent-check app version :report (get-in req [:route-params :hash]))
           req))))}))
