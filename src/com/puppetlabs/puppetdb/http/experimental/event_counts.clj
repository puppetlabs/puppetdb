;; TODO docs
(ns com.puppetlabs.puppetdb.http.experimental.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as event-counts]
            [cheshire.core :as json])
  (:use     [com.puppetlabs.jdbc :only (with-transacted-connection)]
            [com.puppetlabs.middleware :only [verify-accepts-json validate-query-params]]
            [net.cgrand.moustache :only [app]]))

(defn produce-body
  ;; TODO docs
  [{query         "query"
    summarize-by  "summarize-by"
    counts-filter "counts-filter"
    count-by      "count-by"
    :as query-params}
   db]
  (try
    (let [query         (json/parse-string query true)
          counts-filter (if counts-filter (json/parse-string counts-filter true))]
      (with-transacted-connection db
        (-> query
            (event-counts/query->sql summarize-by {:counts-filter counts-filter :count-by count-by})
            (event-counts/query-event-counts)
            (pl-http/json-response))))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (produce-body params (:scf-db globals)))}))

(def event-counts-app
  "Ring app for querying for summary information about resource events."
  (-> routes
      verify-accepts-json
      (validate-query-params {:required ["query" "summarize-by"]
                              :optional ["counts-filter" "count-by"]})))
