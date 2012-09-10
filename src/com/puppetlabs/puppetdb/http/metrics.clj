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
(ns com.puppetlabs.puppetdb.http.metrics
  (:require [clojure.java.jmx :as jmx]
            [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr])
  (:use [clj-http.util :only (url-encode)]
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
  (-> (all-mbean-names)
      (linkify-names)
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

   ["mbean" name]
   {:get (fn [req] (get-mbean name))}))

(def metrics-app
  (pl-http/must-accept-type routes "application/json"))
