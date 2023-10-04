(ns puppetlabs.puppetdb.client
  (:require
   [clojure.tools.logging :as log]
   [puppetlabs.http.client.sync :as http-client]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.command.constants :refer [command-names]]
   [puppetlabs.puppetdb.schema :refer [defn-validated]]
   [puppetlabs.puppetdb.time :as t]
   [puppetlabs.puppetdb.utils :as utils]
   [schema.core :as s])
  (:import
   (java.net HttpURLConnection)))

(defn get-metric [base-url metric-name]
  (let [url (str (utils/base-url->str base-url)
                 "/mbeans/"
                 (java.net.URLEncoder/encode metric-name "UTF-8"))]
    (:body
     (http-client/get url {:throw-exceptions false
                            :content-type :json
                            :character-encoding "UTF-8"
                            :accept :json}))) )

(defn-validated submit-command-via-http!
  "Submits `payload` as a valid command of type `command` and
   `version` to the PuppetDB instance specified by `host` and
   `port`. The `payload` will be converted to JSON before
   submission. Alternately accepts a command-map object (such as those
   returned by `parse-command`). Returns the server response."
  ([base-url
    certname :- s/Str
    command :- s/Str
    version :- s/Int
    payload]
   (submit-command-via-http! base-url certname command version payload nil {}))
  ([base-url
    certname :- s/Str
    command :- s/Str
    version :- s/Int
    payload
    timeout]
   (submit-command-via-http! base-url certname command version payload timeout {}))
  ([base-url
    certname :- s/Str
    command :- s/Str
    version :- s/Int
    payload :- {s/Any s/Any}
    timeout
    ssl-opts :- {s/Keyword s/Str}]
   (let [body (json/generate-string payload)
         url-params (utils/cmd-url-params {:command command
                                           :version version
                                           :certname certname
                                           :producer-timestamp (-> payload
                                                                   :producer_timestamp
                                                                   t/to-string)
                                           :timeout timeout})
         url (str (utils/base-url->str base-url) url-params)
         post-opts (merge {:body body
                           :as :text
                           :headers {"Content-Type" "application/json"}}
                    ;       :throw-exceptions false
                    ;       :content-type :json
                    ;       :character-encoding "UTF-8"
                    ;       :accept :json}
                          (select-keys ssl-opts [:ssl-cert :ssl-key :ssl-ca-cert]))]
     (http-client/post url post-opts))))

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
                 nil
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
                 nil
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
                   nil
                   ssl-opts)]
     (when-not (= HttpURLConnection/HTTP_OK (:status result))
       (log/error result)))))
