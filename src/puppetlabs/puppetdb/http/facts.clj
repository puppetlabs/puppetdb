(ns puppetlabs.puppetdb.http.facts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query.facts :as facts]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.query :as query]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.http :as http]))

(defn query-app
  [version]
  (app
   [&]
   {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                 (produce-streaming-body
                  :facts
                  version
                  (params "query")
                  paging-options
                  (:scf-read-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(defn build-facts-app
  [query-app]
  (app
   []
   (verify-accepts-json query-app)

   [fact value &]
   (comp query-app
         (partial http-q/restrict-fact-query-to-name fact)
         (partial http-q/restrict-fact-query-to-value value))

   [fact &]
   (comp query-app
         (partial http-q/restrict-fact-query-to-name fact))))

(defn facts-app
  [version]
  (build-facts-app
   (-> (query-app version)
     (validate-query-params
      {:optional (cons "query" paging/query-params)})
     wrap-with-paging-options)))
