(ns puppetlabs.puppetdb.client
  (:require [puppetlabs.puppetdb.http :as http]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.reports :as reports]
            [clj-http.client :as http-client]
            [clojure.string :as str]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]))

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
   (submit-command-via-http! base-url certname command version payload nil))
  ([base-url
    certname :- s/Str
    command :- s/Str
    version :- s/Int
    payload :- {s/Any s/Any}
    timeout]
   (let [body (json/generate-string payload)
         url-params (utils/cmd-url-params {:command command
                                           :version version
                                           :certname certname
                                           :producer-timestamp (-> payload
                                                                   :producer_timestamp
                                                                   str)
                                           :timeout timeout})
         url (str (utils/base-url->str base-url) url-params)]
     (http-client/post url {:body body
                            :throw-exceptions false
                            :content-type :json
                            :character-encoding "UTF-8"
                            :accept :json}))))

(defn-validated submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   catalog-payload]
  (let [result (submit-command-via-http!
                 base-url
                 certname
                 (command-names :replace-catalog)
                 command-version
                 catalog-payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))

(defn-validated submit-report
  "Send the given wire-format `report` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   command-version :- s/Int
   report-payload]
  (let [result (submit-command-via-http!
                 base-url
                 certname
                 (command-names :store-report)
                 command-version
                 report-payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))

(defn-validated submit-facts
  "Send the given wire-format `facts` (associated with `host`) to a
   command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   certname :- s/Str
   facts-version :- s/Int
   fact-payload]
  (let [result  (submit-command-via-http!
                  base-url
                  certname
                  (command-names :replace-facts)
                  facts-version
                  fact-payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))
