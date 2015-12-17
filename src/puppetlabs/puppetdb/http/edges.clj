(ns puppetlabs.puppetdb.http.edges
  (:require [net.cgrand.moustache :refer [app]]
            [puppetlabs.puppetdb.http.query :as http-q]
            [puppetlabs.puppetdb.query.paging :as paging]))

(defn edges-app
  ([version] (edges-app version true))
  ([version restrict-to-active-nodes & optional-handlers]
   (let [handler (if restrict-to-active-nodes
                   http-q/restrict-query-to-active-nodes
                   identity)
         handlers (cons handler optional-handlers)
         param-spec {:optional paging/query-params}]
     (app
      [""]
      (http-q/query-route-from "edges" version param-spec handlers)))))
