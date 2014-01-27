(ns com.puppetlabs.puppetdb.http.v3.events
  (:import (java.sql Timestamp))
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.utils :as pl-utils]
            [com.puppetlabs.puppetdb.query.events :as query]
            [cheshire.core :as json]
            [ring.util.response :as rr]
            [com.puppetlabs.puppetdb.query.paging :as paging])
  (:use [net.cgrand.moustache :only [app]]
        com.puppetlabs.middleware
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]
        [com.puppetlabs.http :only (parse-boolean-query-param)]
        [com.puppetlabs.puppetdb.http :only (query-result-response)]))

(defn produce-body
  "Given a `limit`, a query and a database connection, return a Ring response
  with the query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned.

  If the query would return more than `limit` results, `status-internal-error`
  is returned."
  [limit query query-options paging-options db]
  {:pre [(and (integer? limit) (>= limit 0))]}
  (try
    (with-transacted-connection db
      (-> query
          (json/parse-string true)
          ((partial query/query->sql query-options))
          ((partial query/limited-query-resource-events limit paging-options))
          (query-result-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(defn validate-distinct-options!
  "Validate the query params related to a `distinct-resources` query.  Return a
  map containing the validated `distinct-resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params]
  {:pre [(map? params)]
   :post [(map? %)
          (every? (partial contains? %) #{:distinct-resources? :distinct-start-time :distinct-end-time})
          (pl-utils/boolean? (:distinct-resources? %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct-start-time %))
          ((some-fn (partial instance? Timestamp) nil?) (:distinct-end-time %))]}
  (let [distinct-params ["distinct-resources" "distinct-start-time" "distinct-end-time"]]
    (cond
      (not-any? #(contains? params %) distinct-params)
      {:distinct-resources? false
       :distinct-start-time nil
       :distinct-end-time   nil}

      (every? #(contains? params %) distinct-params)
      (let [start (to-timestamp (params "distinct-start-time"))
            end   (to-timestamp (params "distinct-end-time"))]
        (when (some nil? [start end])
          (throw (IllegalArgumentException.
                   (str "query parameters 'distinct-start-time' and 'distinct-end-time' must be valid datetime strings: "
                        (params "distinct-start-time") " "
                        (params "distinct-end-time")))))
        {:distinct-resources? (parse-boolean-query-param params "distinct-resources")
         :distinct-start-time start
         :distinct-end-time   end})

      :else
      (throw (IllegalArgumentException.
               "'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'")))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals paging-options]}]
            (try
              (let [query-options (validate-distinct-options! params)
                    limit         (:event-query-limit globals)]
                (produce-body
                  limit
                  (params "query")
                  query-options
                  paging-options
                  (:scf-db globals)))
              (catch IllegalArgumentException e
                (pl-http/error-response e))))}))

(def events-app
  "Ring app for querying events"
  (-> routes
    verify-accepts-json
    (validate-query-params {:required ["query"]
                            :optional (concat
                                        ["distinct-resources"
                                         "distinct-start-time"
                                         "distinct-end-time"]
                                        paging/query-params)})
    wrap-with-paging-options))
