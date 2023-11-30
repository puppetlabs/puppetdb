(ns puppetlabs.puppetdb.client
  (:require
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [puppetlabs.http.client.sync :as http]
   [puppetlabs.puppetdb.command.constants :refer [command-names]]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.schema :refer [defn-validated]]
   [puppetlabs.puppetdb.time :as t]
   [puppetlabs.puppetdb.utils :as utils]
   [schema.core :as s])
  (:import
   (java.net HttpURLConnection)
   (java.io InputStream)))

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

(defn get-metric [base-url metric-name]
  (let [url (str (utils/base-url->str base-url)
                 "/mbeans/"
                 (java.net.URLEncoder/encode ^String metric-name "UTF-8"))]
    (:body (http/get url {:throw-exceptions false
                          :content-type :json
                          :character-encoding "UTF-8"
                          :accept :json}))) )

(defn- post-json-string [url body opts]
  ;; Unlisted, valid keys: ssl-cert ssl-key ssl-ca-cert
  ;; Intentionally ignores unrecognized keys
  ;;
  ;; This client is currently much slower than the JDK's (which we now
  ;; use in benchmark -- discovered while testing with large servers),
  ;; but we need it because the current benchmark client's
  ;; configuration won't work with fips.
  (http/post url (merge {:body body
                         :as :text
                         :headers {"Content-Type" "application/json"}}
                        (select-keys opts [:ssl-cert :ssl-key :ssl-ca-cert]))))

(defn submit-command-via-http!
  "Submits `payload` as a valid command of type `command` and
   `version` to the PuppetDB instance specified by `host` and
   `port`. The `payload` will be converted to JSON before
   submission. Alternately accepts a command-map object (such as those
   returned by `parse-command`). Returns the server response."
  [base-url certname command version payload & {:keys [timeout post]
                                                :or {post post-json-string}
                                                :as opts}]
  (when-let [extra (seq (set/difference (-> opts keys set)
                                        #{:timeout :post :ssl-cert :ssl-key :ssl-ca-cert}))]
    (throw (IllegalArgumentException. (str "Unexpected options: " (pr-str extra)))))
  (let [stamp (-> payload :producer_timestamp t/to-string)
        url (str (utils/base-url->str base-url)
                 (utils/cmd-url-params {:command command
                                        :version version
                                        :certname certname
                                        :producer-timestamp stamp
                                        :timeout timeout}))]
    (post url (-> payload json/generate-string) opts)))

(defn-validated submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   catalog-payload
   opts]
  ;; See submit-command-via-http! for valid opts (checked there)
  (let [result (submit-command-via-http! base-url certname (command-names :replace-catalog)
                                         command-version catalog-payload opts)]
    (when-not (= HttpURLConnection/HTTP_OK (:status result))
      (log/error result))))

(defn-validated submit-report
  "Send the given wire-format `report` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   report-payload
   opts]
  ;; See submit-command-via-http! for valid opts (checked there)
  (let [result (submit-command-via-http! base-url certname (command-names :store-report)
                                         command-version report-payload opts)]
    (when-not (= HttpURLConnection/HTTP_OK (:status result))
      (log/error result))))

(defn-validated submit-facts
  "Send the given wire-format `facts` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  ([base-url certname facts-version fact-payload]
   (submit-facts base-url certname facts-version fact-payload nil))
  ([base-url :- utils/base-url-schema
    certname :- s/Str
    facts-version :- s/Int
    fact-payload
    opts]
   ;; See submit-command-via-http! for valid opts (checked there)
   (let [result  (submit-command-via-http! base-url certname (command-names :replace-facts)
                                           facts-version fact-payload opts)]
     (when-not (= HttpURLConnection/HTTP_OK (:status result))
       (log/error result)))))

(defn submit-query
  "Send a query to the puppetdb `query` endpoint and return the results."
  [base-url
   query-payload
   opts]
  (when-let [extra (seq (set/difference (-> opts keys set)
                                        #{:timeout :post :ssl-cert :ssl-key :ssl-ca-cert}))]
    (throw (IllegalArgumentException. (str "Unexpected options: " (pr-str extra)))))
  (let [url (str (utils/base-url->str base-url)
                 (utils/query-url-params {:query (json/generate-string query-payload)}))
        result (http/get url opts)
        ;; The result :body is a ContentInputStream that we need to read the bytes out of.
        body (String. ^"[B" (.readAllBytes ^InputStream (:body result)))]
    (if (= HttpURLConnection/HTTP_OK (:status result))
      (json/parse-string body)
      (log/error {:result result :body body}))))

(set! *warn-on-reflection* warn-on-reflection-orig)
