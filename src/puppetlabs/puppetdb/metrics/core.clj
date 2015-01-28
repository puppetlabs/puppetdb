(ns puppetlabs.puppetdb.metrics.core
  (:require [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [clj-http.util :refer [url-encode]]
            [cheshire.custom :refer [JSONable]]
            [clojure.java.jmx :as jmx]
            [clojure.string :as s]
            [puppetlabs.puppetdb.middleware :as mid]))

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

              (instance? java.util.HashMap v)
              [k (filter-mbean (into {} v))]

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
  (zipmap names (map #(format "/mbeans/%s" (url-encode %)) names)))

(defn mbean-names
  "Returns a JSON array of all MBean names"
  [_]
  (->> (all-mbean-names)
       (linkify-names)
       (into (sorted-map))
       (http/json-response)))

(defn get-mbean
  "Returns the attributes of a given MBean"
  [name]
  (if ((all-mbean-names) name)
    (-> (jmx/mbean name)
        (filter-mbean)
        (http/json-response))
    (rr/status (rr/response "No such mbean")
               http/status-not-found)))

(defn convert-shortened-mbean-name
  "Middleware that converts the given / separated mbean name from a shortend 'commands' type
   to the longer form needed by the metrics beans."
  [names-coll]
  (fn [req]
    (-> (s/join "/" names-coll)
        (get-mbean))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map 

(def list-mbeans
  "Function for validating the request then getting the list of mbeans currently known
   by the application"
  (-> mbean-names
      mid/verify-accepts-json
      mid/validate-no-query-params))

(defn mbean
  "Function for getting a specific mbean, identified by `names-coll`. `names-coll`
   is list of mbean name segments"
  [names-coll]
  (-> (convert-shortened-mbean-name names-coll)
      mid/verify-accepts-json
      mid/validate-no-query-params))
