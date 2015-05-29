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
  [globals]
  (let [{:keys [api-version url-prefix scf-read-db]} globals]
    (app
     []
     {:get (fn [{:keys [params paging-options]}]
        (let [{:strs [query]} params]
          (produce-streaming-body :reports api-version query paging-options scf-read-db url-prefix)))})))

(defn report-data-responder
  "Respond with either metrics or logs for a given report hash.
  `entity` should be either :metrics or :logs."
  [globals entity hash]
  (let [{:keys [api-version url-prefix scf-read-db]} globals]
    (app
     []
     {:get (fn [_]
             (let [query (json/generate-string ["=" "hash" hash])]
               (produce-streaming-body entity api-version query {} scf-read-db url-prefix)))})))

(defn reports-app
  [globals]
  (app
   [hash "events" &]
   (comp (e/events-app globals)
         (partial http-q/restrict-query-to-report hash))

   [hash "metrics" &]
   (-> (report-data-responder globals :report-metrics hash)
       validate-no-query-params
       verify-accepts-json)

   [hash "logs" &]
   (-> (report-data-responder globals :report-logs hash)
       validate-no-query-params
       verify-accepts-json)

   [&]
   (-> (report-responder globals)
       (validate-query-params {:optional (cons "query" paging/query-params)})
       wrap-with-paging-options
       verify-accepts-json)))
