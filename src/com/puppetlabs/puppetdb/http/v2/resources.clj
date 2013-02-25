(ns com.puppetlabs.puppetdb.http.v2.resources
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.resource :as r]
            [com.puppetlabs.puppetdb.http.query :as http-q]
            [ring.util.response :as rr]
            [cheshire.core :as json])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  "Given a a query, and database connection, return a Ring response
  with the query results.

  If the query can't be parsed, a 400 is returned."
  [query query->sql db]
  (try
    (let [[sql & params] (with-transacted-connection db
                           (-> query
                               (json/parse-string true)
                               (query->sql)))]

      (-> (pl-http/streamed-response buffer
            (r/with-queried-resources sql params #(pl-http/stream-json % buffer)))
          (rr/response)
          (rr/header "Content-Type" "application/json")
          (rr/status pl-http/status-ok)))

    (catch IllegalArgumentException e
      ;; Query compilation error
      (pl-http/error-response e))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))))

(def query-app
  (app
    [&]
    {:get (comp (fn [{:keys [params globals]}]
                  (produce-body (params "query") r/v2-query->sql (:scf-db globals)))
                http-q/restrict-query-to-active-nodes)}))

(def resources-app
  (app
   []
   (verify-accepts-json query-app)

   [type title &]
   (comp query-app (partial http-q/restrict-resource-query-to-type type) (partial http-q/restrict-resource-query-to-title title))

   [type &]
   (comp query-app (partial http-q/restrict-resource-query-to-type type))))
