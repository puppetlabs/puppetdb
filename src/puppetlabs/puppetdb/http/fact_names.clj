(ns puppetlabs.puppetdb.http.fact-names
  (:require [puppetlabs.puppetdb.query.facts :as f]
            [puppetlabs.puppetdb.query.paging :as paging]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.middleware :refer [verify-accepts-json validate-query-params wrap-with-paging-options validate-no-query-params]]
            [puppetlabs.puppetdb.http :refer [query-result-response]]))

(defn get-fact-names
  "Produces a response body containing the list of known facts."
  [{:keys [globals paging-options]}]
  (let [db (:scf-read-db globals)
        facts (with-transacted-connection db
                (f/fact-names paging-options))]
    (query-result-response facts)))

(def routes
  (app
   [""]
   {:get get-fact-names}))

(defn fact-names-app
  [version]
  (-> routes
    verify-accepts-json
    (validate-query-params {:optional paging/query-params})
    wrap-with-paging-options))
