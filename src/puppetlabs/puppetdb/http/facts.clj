(ns puppetlabs.puppetdb.http.facts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  ([globals] (query-app true))
  ([globals restrict-to-active-nodes]
   (app
    [&]
    {:get (comp (fn [{:keys [params paging-options] :as request}]
                  (produce-streaming-body
                   :facts
                   (:api-version globals)
                   (params "query")
                   paging-options
                   (:scf-read-db globals)
                   (:url-prefix globals)))
                (if restrict-to-active-nodes
                  http-q/restrict-query-to-active-nodes
                  identity))})))

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
  ([globals] (facts-app globals true))
  ([globals restrict-to-active-nodes]
   (build-facts-app
    (-> (query-app globals restrict-to-active-nodes)
        (validate-query-params
         {:optional (cons "query" paging/query-params)})
        wrap-with-paging-options))))
