;; ## Exploratory REST endpoints
;;
;; This namespace provides a set of Ring apps that implement a more
;; "exploratory" interface for interacting with PuppetDB data.
;;
(ns com.puppetlabs.puppetdb.http.experimental.explore
  (:require [com.puppetlabs.puppetdb.http.v2.facts :as facts]
            [com.puppetlabs.puppetdb.http.v2.resources :as resources]
            [com.puppetlabs.puppetdb.http.v2.status :as status]
            [cheshire.core :as json])
  (:use com.puppetlabs.middleware
        [net.cgrand.moustache :only [app]]))

(defn- queries-are-different
  [req1 req2]
  (not= (get-in req1 [:params "query"])
        (get-in req2 [:params "query"])))

(defn restrict-query
  "Given a clause that will restrict a query, modify the supplied
  request so that its query parameter is now restricted according to
  the clause."
  [restriction {:keys [params] :as req}]
  {:pre  [(coll? restriction)]
   :post [(queries-are-different req %)]}
  (let [restricted-query (if-let [query (params "query")]
                           (let [q (json/parse-string query true)]
                             (conj restriction q))
                           restriction)]
    (assoc-in req [:params "query"] (json/generate-string restricted-query))))

(defn restrict-query-to-node
  "Restrict the query parameter of the supplied request so that it
  only returns results for the supplied node"
  [node req]
  {:pre  [(string? node)]
   :post [(queries-are-different req %)]}
  (restrict-query ["and"
                   ["=" "certname" node]
                   ["=" ["node" "active"] true]]
                  req))

(defn restrict-query-to-fact
  "Restrict the query parameter of the supplied request so that it
  only returns facts with the given name"
  [fact req]
  {:pre  [(string? fact)]
   :post [(queries-are-different req %)]}
  (restrict-query ["and"
                   ["=" "name" fact]]
                  req))

(defn restrict-resource-query-to-type
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given type"
  [type req]
  {:pre  [(string? type)]
   :post [(queries-are-different req %)]}
  (restrict-query ["and"
                   ["=" "type" type]]
                  req))

(defn restrict-resource-query-to-title
  "Restrict the query parameter of the supplied request so that it
  only returns resources with the given title"
  [title req]
  {:pre  [(string? title)]
   :post [(queries-are-different req %)]}
  (restrict-query ["and"
                   ["=" "title" title]]
                  req))

(def resources-routes
  "Routes for resource exploration:

  `../<resource type>` returns all resources of that type, across the
  population

  `../<resource type>/<resource title>` returns all resources of that
  type with the given title, across the population"
  (app
   []
   resources/resources-app

   [type title &]
   (comp resources/resources-app (partial restrict-resource-query-to-type type) (partial restrict-resource-query-to-title title))

   [type &]
   (comp resources/resources-app (partial restrict-resource-query-to-type type))

   ))

(def resources-app (verify-accepts-json resources-routes))

(def facts-routes
  "Routes for fact exploration:

  `../<fact name>` returns all facts with that name, across the
  population"
  (app
   []
   facts/facts-app

   [fact &]
   (comp facts/facts-app (partial restrict-query-to-fact fact))))

(def facts-app (verify-accepts-json facts-routes))

(def node-routes
  "Routes for fact exploration:

  `../<node>/facts` returns all facts for the given node

  `../<node>/facts/<fact name>` returns the given fact for the given
  node

  `../<node>/resources/...` routes the request to `resources-app`, but
  all results are filtered to those associated with the given node

  `../<node>` returns status information for the given node"
  (app
   [node "facts" fact &]
   (comp facts/facts-app (partial restrict-query-to-node node) (partial restrict-query-to-fact fact))

   [node "facts" &]
   (comp facts/facts-app (partial restrict-query-to-node node))

   [node "resources" &]
   (comp resources-app (partial restrict-query-to-node node))

   [node]
   status/status-app))

(def nodes-app (verify-accepts-json node-routes))

