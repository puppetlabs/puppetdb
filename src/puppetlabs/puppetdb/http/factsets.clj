(ns puppetlabs.puppetdb.http.factsets
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.http.facts :as facts]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.middleware :refer [wrap-with-parent-check]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.query-eng :as eng]))

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
    (app
     []
     (http-q/query-route-from "factsets" version param-spec
                              [http-q/restrict-query-to-active-nodes])

     [node]
     (fn [{:keys [globals]}]
       (factset-status version node
                       (select-keys globals [:scf-read-db :warn-experimental :url-prefix])))

     [node "facts" &]
     (-> (comp (facts/facts-app version false (partial http-q/restrict-query-to-node node)))
         (wrap-with-parent-check version :factset node)))))
