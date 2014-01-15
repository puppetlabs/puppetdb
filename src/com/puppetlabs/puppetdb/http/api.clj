(ns com.puppetlabs.puppetdb.http.api
  (:require [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.command :as command]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]
            [com.puppetlabs.puppetdb.version :refer [version update-info]]
            [puppetlabs.kitchensink.core :refer [cond-let]]
            [clj-http.util :refer [url-encode]]
            [cheshire.custom :refer [JSONable]]
            [clojure.java.jmx :as jmx]
            [clojure.string :as s])
  (:use [com.puppetlabs.middleware]
        [net.cgrand.moustache :only [app]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Command

;; Supporting functions for the command submission handler

(defn enqueue-command
  "Enqueue the comman in the request parameters, return a UUID"
  [{:keys [params globals] :as request}]
  (let [uuid (command/enqueue-raw-command! (get-in globals [:command-mq :connection-string])
                                           (get-in globals [:command-mq :endpoint])
                                           (params "payload"))]
    (pl-http/json-response {:uuid uuid})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Version

;; Supporting functions for the current version/latest version handlers

(defn current-version-response
  "Responds with the current version of PuppetDB as a JSON object containing a
  `version` key."
  [_]
  (if-let [version (version)]
    (pl-http/json-response {:version version})
    (pl-http/error-response "Could not find version" 404)))

(defn latest-version-response
  "Responds with the latest version of PuppetDB as a JSON object containing a
  `version` key with the version, as well as a `newer` key which is a boolean
  specifying whether the latest version is newer than the current version."
  [{:keys [globals] :as request}]
  {:pre [(:update-server globals)
         (:product-name  globals)]}
  (let [update-server (:update-server globals)
        product-name  (:product-name globals)]
    (try
      (cond-let [result]
        ;; if we get one of these requests from pe-puppetdb, we always want to
        ;; return 'newer->false' so that the dashboard will never try to
        ;; display info about a newer version being available
        (= product-name "pe-puppetdb")
        (pl-http/json-response {"newer"   false
                                "version" (version)
                                "link"    nil})

        (update-info update-server (:scf-read-db globals))
        (pl-http/json-response result)

        :else
        (do
          (log/debug (format
                       "Unable to determine latest version via update-server: '%s'"
                       update-server))
          (pl-http/error-response "Could not find version" 404)))

      (catch java.io.IOException e
        (log/debug (format "Error when checking for latest version: %s" e))
        (pl-http/error-response
          (format "Error when checking for latest version: %s" e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Metrics

;; Supporting functions for the mbean/metrics related handlers


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

(defn mbean-names
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

(defn convert-shortened-mbean-name
  "Middleware that converts the given / separated mbean name from a shortend 'commands' type
   to the longer form needed by the metrics beans."
  [names-coll]
  (fn [req]
    (let [name  (s/join "/" names-coll)
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
      (get-mbean name'))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map 


(def command
  "Function validating the request then submitting a command"
  (-> enqueue-command
      verify-accepts-json
      verify-checksum
      (validate-query-params {:required ["payload"]
                              :optional ["checksum"]})))

(def current-version
  "Function for validating the request then getting the current (running) version PuppetDB"
  (-> current-version-response
      verify-accepts-json
      validate-no-query-params))

(def latest-version
  "Function for validating the request, then getting latest version of PuppetDB"
  (-> latest-version-response
      verify-accepts-json
      validate-no-query-params))

(def list-mbeans
  "Function for validating the request then getting the list of mbeans currently known
   by the application"
  (-> mbean-names
      verify-accepts-json
      validate-no-query-params))

(defn mbean
  "Function for getting a specific mbean, identified by `names-coll`. `names-coll`
   is list of mbean name segments"
  [names-coll]
  (-> (convert-shortened-mbean-name names-coll)
      verify-accepts-json
      validate-no-query-params))
