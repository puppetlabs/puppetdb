(ns puppetlabs.puppetdb.client
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [puppetlabs.http.client.sync :as pclient]
   [puppetlabs.puppetdb.command.constants :refer [command-names]]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.schema :refer [defn-validated]]
   [puppetlabs.puppetdb.time :as t]
   [puppetlabs.puppetdb.utils :as utils]
   [schema.core :as s])
  (:import
   (com.puppetlabs.ssl_utils SSLUtils)
   (java.net HttpURLConnection URI)
   (java.net.http HttpClient
                  HttpRequest
                  HttpRequest$Builder
                  HttpRequest$BodyPublishers
                  HttpResponse$BodyHandlers)))

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

(defn- ssl-info->context
  [& {:keys [ssl-cert ssl-key ssl-ca-cert]}]
  (SSLUtils/pemsToSSLContext (io/reader ssl-cert)
                             (io/reader ssl-key)
                             (io/reader ssl-ca-cert)))

(defn- build-http-client [& {:keys [ssl-cert] :as opts}]
  (cond-> (HttpClient/newBuilder)
    ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
    ssl-cert (.sslContext (ssl-info->context opts))
    true .build))

;; Until we require requests to provide the client (perhaps we should)
(def ^:private http-client (memoize build-http-client))

(defn- json-request-generator
  ([uri] (.uri ^java.net.http.HttpRequest$Builder (json-request-generator) uri))
  ([] (-> (HttpRequest/newBuilder)
          ;; To follow redirects: (.followRedirects HttpClient$Redirect/NORMAL)
          (.header "Content-Type" "application/json; charset=UTF-8")
          (.header "Accept" "application/json"))))

(defn- string-publisher [s] (HttpRequest$BodyPublishers/ofString s))
(defn- string-handler [] (HttpResponse$BodyHandlers/ofString))

(defn- post-body
  [^HttpClient client
   ^HttpRequest$Builder req-generator
   body-publisher
   response-body-handler]
  (let [res (.send client (-> req-generator (.POST body-publisher) .build)
                   response-body-handler)]
    ;; Currently minimal
    {::jdk-response res
     :status (.statusCode res)}))

(defn get-metric [base-url metric-name]
  (let [url (str (utils/base-url->str base-url)
                 "/mbeans/"
                 (java.net.URLEncoder/encode ^String metric-name "UTF-8"))]

    (:body (pclient/get url {:throw-exceptions false
                             :content-type :json
                             :character-encoding "UTF-8"
                             :accept :json}))) )

(defn submit-command-via-http!
  "Submits `payload` as a valid command of type `command` and
   `version` to the PuppetDB instance specified by `host` and
   `port`. The `payload` will be converted to JSON before
   submission. Alternately accepts a command-map object (such as those
   returned by `parse-command`). Returns the server response."
  [base-url certname command version payload & {:keys [timeout] :as opts}]
  (when-let [extra (seq (set/difference (-> opts keys set)
                                        #{:timeout :ssl-cert :ssl-key :ssl-ca-cert}))]
    (throw (IllegalArgumentException. (str "Unexpected options: " (pr-str extra)))))
  (let [stamp (-> payload :producer_timestamp t/to-string)
        url (str (utils/base-url->str base-url)
                 (utils/cmd-url-params {:command command
                                        :version version
                                        :certname certname
                                        :producer-timestamp stamp
                                        :timeout timeout}))]
    (post-body (http-client (select-keys opts [:ssl-cert :ssl-key :ssl-ca-cert]))
               (json-request-generator (URI. url))
               (-> payload json/generate-string string-publisher)
               (string-handler))))

(defn-validated submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   catalog-payload
   ssl-opts]
  (let [result (submit-command-via-http!
                 base-url
                 certname
                 (command-names :replace-catalog)
                 command-version
                 catalog-payload
                 ssl-opts)]
    (when-not (= HttpURLConnection/HTTP_OK (:status result))
      (log/error result))))

(defn-validated submit-report
  "Send the given wire-format `report` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   report-payload
   ssl-opts]
  (let [result (submit-command-via-http!
                 base-url
                 certname
                 (command-names :store-report)
                 command-version
                 report-payload
                 ssl-opts)]
    (when-not (= HttpURLConnection/HTTP_OK (:status result))
      (log/error result))))

(defn-validated submit-facts
  "Send the given wire-format `facts` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  ([base-url :- utils/base-url-schema
    certname :- s/Str
    facts-version :- s/Int
    fact-payload]
   (submit-facts base-url certname facts-version fact-payload {}))
  ([base-url :- utils/base-url-schema
    certname :- s/Str
    facts-version :- s/Int
    fact-payload
    ssl-opts]
   (let [result  (submit-command-via-http!
                   base-url
                   certname
                   (command-names :replace-facts)
                   facts-version
                   fact-payload
                   ssl-opts)]
     (when-not (= HttpURLConnection/HTTP_OK (:status result))
       (log/error result)))))

(set! *warn-on-reflection* warn-on-reflection-orig)
