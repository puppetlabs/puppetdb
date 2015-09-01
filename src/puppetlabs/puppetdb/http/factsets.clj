(ns puppetlabs.puppetdb.http.factsets
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json
                                                    wrap-with-paging-options
                                                    wrap-with-parent-check]]
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

(defn routes
  [version]
  (let [param-spec {:optional (cons "query" paging/query-params)}]
    (app
      []
      (http-q/query-route :factsets version param-spec http-q/restrict-query-to-active-nodes')

      [node]
      (fn [{:keys [globals]}]
        (factset-status version node (:scf-read-db globals) (:url-prefix globals)))

      [node "facts" &]
      (-> (comp (facts/facts-app version false (partial http-q/restrict-query-to-node node)))
          (wrap-with-parent-check version :factset node)))))

(defn factset-app
  [version]
  (-> (routes version)
      verify-accepts-json
      wrap-with-paging-options))
