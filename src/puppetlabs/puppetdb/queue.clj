(ns puppetlabs.puppetdb.queue
  (:import [java.nio.charset StandardCharsets]
           [java.io InputStreamReader BufferedReader])
  (:require [stockpile :as stock]
            [clj-time.coerce :as tcoerce]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.string :as str]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [throw+]]))

(defn stream->json [stream]
  (try
    (-> stream
        (InputStreamReader. StandardCharsets/UTF_8)
        BufferedReader.
        (json/parse-stream true))
    (catch Exception e
      (throw+ {:kind ::parse-error} e "Error parsing command"))))

(defn metadata-str [received-time command version certname]
  (format "%s_%s_%s_%s" received-time command version certname))

(defn entry->cmd [q {stockpile-entry :entry :as entry}]
  (let [[received-time-ms command version certname] (str/split (stock/entry-meta stockpile-entry) #"_" 4)]
    (assoc entry
           :command command
           :version (Long/parseLong version)
           :certname certname
           :payload (stream->json (stock/stream q stockpile-entry)))))

(defn store-command
  ([q command version certname command-stream]
   (store-command q command version certname command-stream identity))
  ([q command version certname command-stream command-callback]
   (let [current-time (time/now)
         entry (stock/store q
                            command-stream
                            (metadata-str (tcoerce/to-long current-time) command version certname))]
     {:entry entry
      :callback command-callback
      :annotations {:attempts []
                    :id (stock/entry-id entry)
                    :received (kitchensink/timestamp current-time)}})))
