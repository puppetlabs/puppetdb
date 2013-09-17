(ns com.puppetlabs.puppetdb.http.v3.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn query-facts
  "Accepts a `query` and a `db` connection, and returns facts matching the
  query. If the query can't be parsed or is invalid, a 400 error will be
  returned, and a 500 if something else goes wrong."
  [query paging-options db]
  (try
    (with-transacted-connection db
      (let [query   (if query (json/parse-string query true))
            sql     (f/query->sql query)
            facts   (f/query-facts sql paging-options)]
        (query-result-response facts)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals paging-options] :as request}]
                  (query-facts (params "query") paging-options (:scf-db globals)))
            http-q/restrict-query-to-active-nodes)}))

(defn build-facts-app
  [query-app]
  (app
    []
    (verify-accepts-json query-app)

    [fact value &]
    (comp query-app
          (partial http-q/restrict-fact-query-to-name fact)
          (partial http-q/restrict-fact-query-to-value value))

    [fact &]
    (comp query-app (partial http-q/restrict-fact-query-to-name fact))))

(def facts-app
  (build-facts-app
    (-> query-app
      (validate-query-params
        {:optional (cons "query" paging/query-params)})
      wrap-with-paging-options)))
