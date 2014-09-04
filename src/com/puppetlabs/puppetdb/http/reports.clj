(ns com.puppetlabs.puppetdb.http.reports
  (:require [com.puppetlabs.puppetdb.query.reports :as reports]
            [com.puppetlabs.puppetdb.query :as query]
            [com.puppetlabs.puppetdb.query-eng.engine :as qe]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.puppetdb.http :as http]
            [com.puppetlabs.middleware :refer [verify-accepts-json validate-query-params
                                               wrap-with-paging-options]]
            [com.puppetlabs.jdbc :refer [with-transacted-connection get-result-count]]))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (produce-streaming-body
             :reports
            version
            (params "query")
            paging-options
            (:scf-read-db globals)))}))

(defn reports-app
  "Ring app for querying reports"
  [version]
  (-> (routes version)
    verify-accepts-json
    (validate-query-params
      {:optional (cons "query" paging/query-params)})
    wrap-with-paging-options))
