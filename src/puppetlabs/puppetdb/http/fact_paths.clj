(ns puppetlabs.puppetdb.http.fact-paths
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.middleware :refer [validate-query-params]]))

(defn fact-paths-app
  [version]
  (let [param-spec {:optional paging/query-params}]
    {"" (http-q/query-route-from' "fact_paths" version param-spec)}))
