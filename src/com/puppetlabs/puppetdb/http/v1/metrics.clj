;; ## REST endpoint for retrieving JMX data
;;
;; This Ring app allows for the easy retrieval of JMX MBeans (rendered
;; as JSON). It supports the listing of all available MBean names, and
;; retrieving attributes for a particular MBean.
;;
;; ### Request and response formats
;;
;; `GET` requests made to `/metrics/mbeans` will return a JSON Object
;; of all MBean names.
;;
;; `GET` requests made to `/metrics/mean/<name>` will return a JSON
;; Object of all attributes for that MBean. If no MBean is found by
;; that name, a 404 is returned.
;;
;; ### A note on formatting
;;
;; Not all JMX properties are trivially serializable to JSON. JMX
;; Objects can be arbitrary Java objects, and JSON is, well, JSON.
;; For attributes that can't be auto-converted to JSON, we stringify
;; them prior to returning them to the client.
;;
(ns com.puppetlabs.puppetdb.http.v1.metrics
  (:require [clojure.java.jmx :as jmx]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [clojure.string :as s]
            [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use com.puppetlabs.middleware
        [clj-http.util :only (url-encode)]
        [cheshire.custom :only (JSONable)]
        [net.cgrand.moustache :only (app)]))

(defn filter-mbean
  "Converts an mbean to a map. For attributes that can't be converted to JSON,
  return a string representation of the value."
  [mbean]
  {:post [(map? %)]}
  (into {} (for [[k v] mbean]
             (cond
              ;; Nested structures should themselves be filtered
              (map? v)
              [k (filter-mbean v)]

              ;; Cheshire can serialize to JSON anything that
              ;; implements the JSONable protocol
              (satisfies? JSONable v)
              [k v]

              :else
              [k (str v)]))))

(defn all-mbean-names
  "Return a set of all mbeans names"
  []
  {:post [(set? %)]}
  (set (map str (jmx/mbean-names "*:*"))))

(defn linkify-names
  "Return a map of mbean name to a link that will retrieve the
  attributes"
  [names]
  (zipmap names (map #(format "/metrics/mbean/%s" (url-encode %)) names)))

(defn list-mbeans
  "Returns a JSON array of all MBean names"
  [_]
  (->> (all-mbean-names)
       (linkify-names)
       (into (sorted-map))
       (pl-http/json-response)))

(defn get-mbean
  "Returns the attributes of a given MBean"
  [name]
  (if ((all-mbean-names) name)
    (-> (jmx/mbean name)
        (filter-mbean)
        (pl-http/json-response))
    (rr/status (rr/response "No such mbean")
               pl-http/status-not-found)))

(def routes
  (app
   ["mbeans"]
   {:get list-mbeans}

   ["mbean" & names]
   {:get (fn [req]
           (let [name  (s/join "/" names)
                 ;; Backwards-compatibility hacks to allow
                 ;; interrogation of "top-level" metrics like
                 ;; "commands" instead of "/v2/commands"...something
                 ;; we documented as supported, but we broke when we
                 ;; went to versioned apis.
                 name' (cond
                        (.startsWith name "com.puppetlabs.puppetdb.http.server:type=metrics")
                        (s/replace name #"type=metrics" "type=/v2/metrics")

                        (.startsWith name "com.puppetlabs.puppetdb.http.server:type=commands")
                        (s/replace name #"type=commands" "type=/v2/commands")

                        (.startsWith name "com.puppetlabs.puppetdb.http.server:type=facts")
                        (s/replace name #"type=facts" "type=/v2/facts")

                        (.startsWith name "com.puppetlabs.puppetdb.http.server:type=resources")
                        (s/replace name #"type=resources" "type=/v2/resources")

                        :else
                        name)]
             (get-mbean name')))}))

(def metrics-app
  (verify-accepts-json routes))
