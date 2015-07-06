(ns com.puppetlabs.puppetdb.http.fact-names
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only [verify-accepts-json validate-query-params wrap-with-paging-options validate-no-query-params]]
        [com.puppetlabs.puppetdb.http :only [query-result-response]]))

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
  (case version
    :v1 (throw (IllegalArgumentException. "api v1 is retired"))
    :v2 (-> routes
            verify-accepts-json
            (validate-no-query-params))
    (-> routes
        verify-accepts-json
        (validate-query-params {:optional paging/query-params})
        wrap-with-paging-options)))
