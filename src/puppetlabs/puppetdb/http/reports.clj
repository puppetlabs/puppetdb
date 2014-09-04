(ns puppetlabs.puppetdb.http.reports
  (:require [puppetlabs.puppetdb.query.reports :as reports]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query-eng.engine :as qe]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                        wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection get-result-count]]))

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
