(ns puppetlabs.puppetdb.http.resources
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :refer [produce-streaming-body]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options]]))

(defn query-app
  ([globals] (query-app globals true))
  ([{:keys [api-version scf-read-db url-prefix]} restrict-to-active-nodes]
   (app
    [&]
    {:get (comp (fn [{:keys [params paging-options]}]
                  (produce-streaming-body
                   :resources
                   api-version
                   (params "query")
                   paging-options
                   scf-read-db
                   url-prefix))
                (if restrict-to-active-nodes
                  http-q/restrict-query-to-active-nodes
                  identity))})))

(defn build-resources-app
  [query-app]
  (app
   []
   (verify-accepts-json query-app)

   [type title &]
   (comp query-app
         (partial http-q/restrict-resource-query-to-type type)
         (partial http-q/restrict-resource-query-to-title title))

   [type &]
   (comp query-app
         (partial http-q/restrict-resource-query-to-type type))))

(defn resources-app
  ([globals] (resources-app globals true))
  ([globals restrict-to-active-nodes]
     (build-resources-app
      (-> (query-app globals restrict-to-active-nodes)
          (validate-query-params {:optional (cons "query" paging/query-params)})
          wrap-with-paging-options))))
