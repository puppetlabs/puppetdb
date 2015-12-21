(ns puppetlabs.puppetdb.http.facts
  (:require [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]
            [bidi.ring :as bring]))

(defn facts-app
  ([version] (facts-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (let [handler (if restrict-to-active-nodes
                   http-q/restrict-query-to-active-nodes
                   identity)
         handlers (cons handler optional-handlers)
         param-spec {:optional paging/query-params}
         query-route (partial http-q/query-route-from' "facts" version param-spec)]
     {"" 
      (query-route handlers)

      ["/" :fact "/" :value]
      (query-route (concat handlers
                           [(fn [{:keys [route-params] :as req}]
                              (http-q/restrict-fact-query-to-name (:fact route-params) req))
                            (fn [{:keys [route-params] :as req}]
                              (http-q/restrict-fact-query-to-value (:value route-params) req))]))

      ["/" :fact]
      (query-route (concat handlers
                           [(fn [{:keys [route-params] :as req}]
                              (http-q/restrict-fact-query-to-name (:fact route-params) req))]))})))
