(ns com.puppetlabs.puppetdb.http.v2.fact-names
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.http :as pl-http])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn get-fact-names
  "Produces a response body containing the list of known facts."
  [{:keys [globals]}]
  (let [db (:scf-db globals)
        facts (with-transacted-connection db
                (f/fact-names))]
    (pl-http/json-response facts)))

(def routes
  (app
    [""]
    {:get get-fact-names}))

(def fact-names-app
  (-> routes
      verify-accepts-json
      verify-no-paging-params))
