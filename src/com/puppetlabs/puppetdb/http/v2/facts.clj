(ns com.puppetlabs.puppetdb.http.v2.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn retrieve-facts-for-node
  "Produce a response body for a request to lookup facts for `node`."
  [node db]
  (let [facts (with-transacted-connection db
                (f/flat-facts-by-node node))]
    (if (seq facts)
      (pl-http/json-response facts)
      (pl-http/json-response {:error (str "Could not find facts for " node)} pl-http/status-not-found))))

(defn query-facts
  [query db]
  (try
    (with-transacted-connection db
      (let [query (if query (json/parse-string query true))
            sql   (f/query->sql query)
            facts (f/query-facts sql)]
       (pl-http/json-response facts)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (-> (fn [{:keys [params globals] :as request}]
                (query-facts (params "query") (:scf-db globals)))
            (verify-param-exists "query"))}

    [node]
    {:get (fn [{:keys [globals]}]
            (retrieve-facts-for-node node (:scf-db globals)))}))

(def facts-app
  (verify-accepts-json routes))
