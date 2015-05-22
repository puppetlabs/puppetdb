(ns puppetlabs.puppetdb.http.fact-names
  (:require [puppetlabs.puppetdb.query.facts :as f]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options]]
            [puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn get-fact-names
  "Produces a response body containing the list of known facts."
  [{:keys [scf-read-db]}]
  (fn [{:keys [paging-options]}]
    (let [facts (with-transacted-connection scf-read-db
                  (f/fact-names paging-options))]
      (query-result-response facts))))

(defn routes
  [globals]
  (app
   [""]
   {:get (get-fact-names globals)}))

(defn fact-names-app
  [globals]
  (-> (routes globals)
      verify-accepts-json
      (validate-query-params {:optional paging/query-params})
      wrap-with-paging-options))
