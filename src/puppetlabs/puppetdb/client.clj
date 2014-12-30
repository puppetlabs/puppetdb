(ns puppetlabs.puppetdb.client
  (:require [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.reports :as reports]
            [clj-http.client :as http-client]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.kitchensink.core :as kitchensink]
            [schema.core :as s]))

(defn-validated submit-command-via-http!
  "Submits `payload` as a valid command of type `command` and
  `version` to the PuppetDB instance specified by `host` and
  `port`. The `payload` will be converted to JSON before
  submission. Alternately accepts a command-map object (such as those
  returned by `parse-command`). Returns the server response."
  ([base-url
    command :- s/Str
    version :- s/Int
    payload]
     (->> payload
          (command/assemble-command command version)
          (submit-command-via-http! base-url)))
  ([base-url :- utils/base-url-schema
    command-map :- {s/Any s/Any}]
     (let [message (json/generate-string command-map)
           checksum (kitchensink/utf8-string->sha1 message)
           url (str (utils/base-url->str base-url)
                    (format "/commands?checksum=%s" checksum))]
       (http-client/post url {:body               message
                              :throw-exceptions   false
                              :content-type       :json
                              :character-encoding "UTF-8"
                              :accept             :json}))))

(defn-validated submit-catalog
  "Send the given wire-format `catalog` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   command-version :- s/Int
   catalog-payload :- s/Str]
  (let [result (submit-command-via-http!
                base-url
                (command-names :replace-catalog) command-version
                catalog-payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))

(defn-validated submit-report
  "Send the given wire-format `report` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   command-version :- s/Int
   report-payload :- s/Str]
  (let [payload (-> report-payload
                    json/parse-string
                    reports/sanitize-report)
        result  (submit-command-via-http!
                 base-url
                 (command-names :store-report) command-version
                 payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))

(defn-validated submit-facts
  "Send the given wire-format `facts` (associated with `host`) to a
  command-processing endpoint located at `puppetdb-host`:`puppetdb-port`."
  [base-url :- utils/base-url-schema
   facts-version :- s/Int
   fact-payload :- s/Str]
  (let [payload (case facts-version
                  1 fact-payload
                  (json/parse-string fact-payload))
        result  (submit-command-via-http!
                 base-url
                 (command-names :replace-facts)
                 facts-version
                 payload)]
    (when-not (= http/status-ok (:status result))
      (log/error result))))
