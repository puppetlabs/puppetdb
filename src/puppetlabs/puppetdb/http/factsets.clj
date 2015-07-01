(ns puppetlabs.puppetdb.http.factsets
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params
                                                    wrap-with-paging-options wrap-with-parent-check]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for `node`."
  [api-version node db url-prefix]
  (let [factset (first
                 (eng/stream-query-result :factsets
                                          api-version
                                          ["=" "certname" node]
                                          {}
                                          db
                                          url-prefix))]
    (if factset
      (http/json-response factset)
      (http/status-not-found-response "factset" node))))

(defn build-factsets-app
  [globals entity]
  (fn [{:keys [params paging-options]}]
    (eng/produce-streaming-body
     entity
     (:api-version globals)
     (params "query")
     paging-options
     (:scf-read-db globals)
     (:url-prefix globals))))

(defn routes
  [{:keys [url-prefix scf-read-db api-version] :as globals}]
  (app
   []
   {:get (comp (build-factsets-app globals :factsets)
               http-q/restrict-query-to-active-nodes)}

   [node]
   (constantly
    (factset-status api-version node scf-read-db url-prefix))

   [node "facts" &]
   (-> (comp (facts/facts-app globals false) (partial http-q/restrict-query-to-node node))
       (wrap-with-parent-check globals :factset node))))

(defn factset-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params
       {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options))
