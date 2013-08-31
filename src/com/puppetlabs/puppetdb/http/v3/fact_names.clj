(ns com.puppetlabs.puppetdb.http.v3.fact-names
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.http :as pl-http])
  (:use [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [net.cgrand.moustache :only [app]]
        [com.puppetlabs.middleware :only [verify-accepts-json wrap-with-paging-options]]))

(defn get-fact-names
  "Produces a response body containing the list of known facts."
  [{:keys [globals paging-options]}]
  (let [db (:scf-db globals)
        facts (with-transacted-connection db
                (f/fact-names paging-options))]
    (pl-http/json-response facts)))

(def routes
  (app
    [""]
    {:get get-fact-names}))

(def fact-names-app
  (-> routes
    verify-accepts-json
    wrap-with-paging-options))
