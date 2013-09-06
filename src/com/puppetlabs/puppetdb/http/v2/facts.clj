(ns com.puppetlabs.puppetdb.http.v2.facts
  (:require [com.puppetlabs.puppetdb.query.facts :as f]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn query-facts
  "Accepts a `query` and a `db` connection, and returns facts matching the
  query. If the query can't be parsed or is invalid, a 400 error will be returned, and a 500 if
  something else goes wrong."
  [query db]
  (try
    (let [[sql & params] (with-transacted-connection db
                           (-> query
                               (json/parse-string true)
                               (f/query->sql)))]
      (pl-http/json-response*
       (pl-http/streamed-response buffer
        (with-transacted-connection db
          (f/with-queried-facts sql params #(pl-http/stream-json % buffer))))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(def query-app
  (app
   [&]
   {:get (comp (fn [{:keys [params globals] :as request}]
                 (query-facts (params "query") (:scf-db globals)))
               http-q/restrict-query-to-active-nodes)}))

(def facts-app
  (app
   []
   (verify-accepts-json query-app)

   [fact value &]
   (comp query-app (partial http-q/restrict-fact-query-to-name fact) (partial http-q/restrict-fact-query-to-value value))

   [fact &]
   (comp query-app (partial http-q/restrict-fact-query-to-name fact))))
