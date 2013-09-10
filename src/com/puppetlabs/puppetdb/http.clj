(ns com.puppetlabs.puppetdb.http
  (:require [ring.util.response :as rr])
  (:use [com.puppetlabs.http :only [json-response]]))

(def header-map
  "Maps the legal keys from a puppetdb query response object to the
  corresponding HTTP header names."
  {:count "X-Records"})

(defn header-for-key
  "Given a key from a PuppetDB query response, returns the HTTP header that
  should be used in the HTTP response."
  [k]
  {:pre [(contains? header-map k)]
   :post [(string? %)]}
  (header-map k))

(defn add-headers
  "Given a Ring response and a PuppetDB query result map, returns
  an updated Ring response with the headers added."
  [response query-result]
  {:pre  [(map? query-result)]
   :post [(rr/response? %)]}
  (reduce
    (fn [r [k v]] (rr/header r (header-for-key k) v))
    response
    query-result))

(defn query-result-response
  "Given a PuppetDB query result map (as returned by `query/execute-query`),
  returns a Ring HTTP response object."
  [query-result]
  {:pre [(map? query-result)
         (contains? query-result :result)]
   :post [(rr/response? %)]}
  (->
    (json-response (:result query-result))
    (add-headers (dissoc query-result :result))))
