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
                                                    wrap-with-paging-options]]))

(defn report-responder
  "Respond with reports."
  [version]
  (app
   []
   {:get
    (fn [{:keys [params globals paging-options]}]
      (let [{db :scf-read-db url-prefix :url-prefix} globals
            {:strs [query]} params]
        (produce-streaming-body :reports version query paging-options db url-prefix)))}))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
  `entity` should be either :metrics or :logs."
  [version entity hash]
  (app
   []
   {:get
    (fn [{:keys [globals]}]
      (let [{db :scf-read-db url-prefix :url-prefix} globals
            query (json/generate-string ["=" "hash" hash])]
        (produce-streaming-body entity version query {} db url-prefix)))}))

(defn reports-app
  [version]
  (app
   [hash "events" &]
   (comp (e/events-app version)
         (partial http-q/restrict-query-to-report hash))

   [hash "metrics" &]
   (-> (report-data-responder version :report-metrics hash)
       validate-no-query-params
       verify-accepts-json)

   [hash "logs" &]
   (-> (report-data-responder version :report-logs hash)
       validate-no-query-params
       verify-accepts-json)

   [&]
   (-> (report-responder version)
       (validate-query-params {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options
       verify-accepts-json)))
