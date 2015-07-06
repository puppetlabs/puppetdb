(ns com.puppetlabs.puppetdb.http.events
  (:import (java.sql Timestamp))
  (:require [com.puppetlabs.http :as pl-http]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.query.events :as events]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.puppetdb.query.paging :as paging]
            [com.puppetlabs.puppetdb.query :as query]
            [clj-time.coerce :refer [to-timestamp]]
            [com.puppetlabs.middleware :as middleware]
            [net.cgrand.moustache :refer [app]]
            [com.puppetlabs.jdbc :as jdbc]
            [com.puppetlabs.puppetdb.http :as http]))

(defn validate-distinct-options!
  "Validate the HTTP query params related to a `distinct-resources` query.  Return a
  map containing the validated `distinct-resources` options, parsed to the correct
  data types.  Throws `IllegalArgumentException` if any arguments are missing
  or invalid."
  [params]
  {:pre [(map? params)]
   :post [(map? %)
          (every? (partial contains? %) #{:distinct-resources? :distinct-start-time :distinct-end-time})
          (kitchensink/boolean? (:distinct-resources? %))
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
        {:distinct-resources? (pl-http/parse-boolean-query-param params "distinct-resources")
         :distinct-start-time start
         :distinct-end-time   end})

      :else
      (throw (IllegalArgumentException.
              "'distinct-resources' query parameter requires accompanying parameters 'distinct-start-time' and 'distinct-end-time'")))))

(defn produce-body
  "Given a query, options and a database connection, return a Ring response with the
  query results.

  If the query can't be parsed, an HTTP `Bad Request` (400) is returned."
  [version json-query query-options paging-options db]
  (try
    (jdbc/with-transacted-connection db
      (let [parsed-query (json/parse-strict-string json-query true)
            {[sql & params] :results-query
             count-query    :count-query} (events/query->sql version query-options parsed-query paging-options)
            resp (pl-http/stream-json-response
                  (fn [f]
                    (jdbc/with-transacted-connection db
                      (query/streamed-query-result version sql params
                                                   (comp f (events/munge-result-rows version))))))]
        (if count-query
          (http/add-headers resp {:count (jdbc/get-result-count count-query)})
          resp)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))))

(defn routes
  [version]
  (app
   [""]
   {:get (fn [{:keys [params globals paging-options]}]
           (try
             (let [query-options (validate-distinct-options! params)]
               (produce-body
                version
                (params "query")
                query-options
                paging-options
                (:scf-read-db globals)))
             (catch IllegalArgumentException e
               (pl-http/error-response e))))}))

(defn events-app
  "Ring app for querying events"
  [version]
  (-> (routes version)
      middleware/verify-accepts-json
      (middleware/validate-query-params {:required ["query"]
                                         :optional (concat
                                                    ["distinct-resources"
                                                     "distinct-start-time"
                                                     "distinct-end-time"]
                                                    paging/query-params)})
      middleware/wrap-with-paging-options))
