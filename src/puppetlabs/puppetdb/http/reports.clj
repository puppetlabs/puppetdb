(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.http.events :as e]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :refer [validate-no-query-params
                                                    wrap-with-parent-check]]))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
   `entity` should be either :metrics or :logs."
  [version entity hash]
  (app
    []
    (fn [{:keys [globals]}]
      (let [query ["from" entity ["=" "hash" hash]]]
        (produce-streaming-body version {:query query}
                                (select-keys globals [:scf-read-db
                                                      :url-prefix
                                                      :pretty-print
                                                      :warn-experimental]))))))

(defn reports-app
  [version & optional-handlers]
  (let [param-spec {:optional paging/query-params}]
    (app
     []
     (http-q/query-route-from "reports" version param-spec optional-handlers)

     [hash "events" &]
     (-> (e/events-app version (partial http-q/restrict-query-to-report hash))
         (wrap-with-parent-check version :report hash))

     [hash "metrics" &]
     (-> (report-data-responder version "report_metrics" hash)
         validate-no-query-params
         (wrap-with-parent-check version :report hash))

     [hash "logs" &]
     (-> (report-data-responder version "report_logs" hash)
         validate-no-query-params
         (wrap-with-parent-check version :report hash)))))
