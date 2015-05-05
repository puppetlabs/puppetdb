(ns puppetlabs.puppetdb.http.facts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  ([version] (query-app true))
  ([version restrict-to-active-nodes]
   (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                  (produce-streaming-body
                   :facts
                   version
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
  ([version] (facts-app version true))
  ([version restrict-to-active-nodes]
   (build-facts-app
    (-> (query-app version restrict-to-active-nodes)
        (validate-query-params
         {:optional (cons "query" paging/query-params)})
        wrap-with-paging-options))))
