(ns puppetlabs.puppetdb.http.factsets
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-parent-check wrap-with-parent-check']]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]
            [bidi.ring :as bring]))

(defn factset-status
  "Produces a response body for a request to retrieve the factset for `node`."
  [api-version node options]
  (let [factset (first
                 (eng/stream-query-result api-version
                                          ["from" "factsets" ["=" "certname" node]]
                                          {}
                                          options))]
    (if factset
      (http/json-response factset)
      (http/status-not-found-response "factset" node))))

(defn factset-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" 
     (http-q/query-route-from' "factsets" version param-spec
                              [http-q/restrict-query-to-active-nodes])

     ["/" :node]
     (fn [{:keys [globals route-params]}]
       (factset-status version (:node route-params)
                       (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))

     ["/" :node "/facts"]
     (bring/wrap-middleware (facts/facts-app version false http-q/restrict-query-to-node')
                                    (fn [app]
                                      (wrap-with-parent-check' app version :factset)))}))
