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
    (with-open [command-stream (stock/stream q stockpile-entry)]
      (-> entry
          (assoc
           :command command
           :version (Long/parseLong version)
           :certname certname
           :payload (stream->json command-stream))
          (assoc-in [:annotations :received] (-> received-time-ms
                                                 Long/parseLong
                                                 tcoerce/from-long
                                                 kitchensink/timestamp))))))

(defn stockpile-entry->entry
  ([stockpile-entry]
   (stockpile-entry->entry stockpile-entry identity))
  ([stockpile-entry callback]
   {:entry stockpile-entry
    :callback callback
    :annotations {:attempts []
                  :id (stock/entry-id stockpile-entry)}}))

(defn store-command
  ([q command version certname command-stream]
   (store-command q command version certname command-stream identity))
  ([q command version certname command-stream command-callback]
   (let [current-time (time/now)]
     (-> q
         (stock/store command-stream
                      (metadata-str (tcoerce/to-long current-time) command version certname))
         (stockpile-entry->entry command-callback)
         (assoc-in [:annotations :received] (kitchensink/timestamp current-time))))))

(defn ack-command
  [q command]
  (stock/discard q (:entry command)))
