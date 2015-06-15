(ns puppetlabs.puppetdb.http.fact-names
  (:require [puppetlabs.puppetdb.query.facts :as f]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn get-fact-names
  "Produces a response body containing the list of known facts."
  [globals]
  (fn [{:keys [paging-options]}]
    (let [db (:scf-read-db globals)
          facts (with-transacted-connection db
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
      mid/verify-accepts-json
      (mid/validate-query-params {:optional paging/query-params})
      mid/wrap-with-paging-options))
