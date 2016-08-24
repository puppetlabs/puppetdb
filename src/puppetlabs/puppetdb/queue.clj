(ns puppetlabs.puppetdb.queue
  (:import [java.nio.charset StandardCharsets]
           [java.io InputStreamReader BufferedReader InputStream]
           [java.util TreeMap HashMap])
  (:require [clojure.string :as str :refer [re-quote-replacement]]
            [stockpile :as stock]
            [clj-time.coerce :as tcoerce]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.command.constants :as constants]
            [clojure.string :as str :refer [re-quote-replacement]]
            [clj-time.core :as time]
            [puppetlabs.kitchensink.core :as kitchensink]
            [slingshot.slingshot :refer [throw+]]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protos]))

(def metadata-command-names
  (vals constants/command-names))

(defn stream->json [^InputStream stream]
  (try
    (-> stream
        (InputStreamReader. StandardCharsets/UTF_8)
        BufferedReader.
        (json/parse-stream true))
    (catch Exception e
      (throw+ {:kind ::parse-error} e "Error parsing command"))))

(defn metadata-str [received command version certname]
  (format "%d_%s_%d_%s.json"
          (tcoerce/to-long received) command version certname))

(defn- metadata-rx [valid-commands]
  (re-pattern (str
               "([0-9]+)_("
               (str/join "|" (map #(format "(?:%s)" (re-quote-replacement %))
                                  valid-commands))
               ")_([0-9]+)_(.*)\\.json")))

(defn metadata-parser
  ([] (metadata-parser metadata-command-names))
  ([valid-commands]
   ;; NOTE: changes here may affect the DLO, e.g. it currently assumes
   ;; the trailing .json.
   (let [rx (metadata-rx valid-commands)]
     (fn [s]
       (when-let [[_ stamp command version certname] (re-matches rx s)]
         (and certname
              {:stamp stamp
               :version version
               :command command
               :certname certname}))))))

(def parse-metadata (metadata-parser))

(defrecord CommandRef [id command version certname received callback annotations delete?])

(defn cmdref->entry [{:keys [id command version certname received]}]
  (stock/entry id (metadata-str received command version certname)))

(defn entry->cmdref [entry]
  (let [{:keys [stamp command version certname]} (-> entry
                                                     stock/entry-meta
                                                     parse-metadata)
        received (-> stamp
                     Long/parseLong
                     tcoerce/from-long
                     kitchensink/timestamp)]
    (map->CommandRef {:id (stock/entry-id entry)
                      :command command
                      :version (Long/parseLong version)
                      :certname certname
                      :received received
                      :callback identity
                      :annotations {:id (stock/entry-id entry)
                                    :received received
                                    :attempts []}})))

(defn cmdref->cmd [q cmdref]
  (let [entry (cmdref->entry cmdref)]
    (with-open [command-stream (stock/stream q entry)]
      (assoc cmdref
             :payload (stream->json command-stream)
             :entry entry))))

(defn store-command
  ([q command version certname command-stream]
   (store-command q command version certname command-stream identity))
  ([q command version certname command-stream command-callback]
   (let [current-time (time/now)
         entry (stock/store q
                            command-stream
                            (metadata-str current-time command version certname))]

     (map->CommandRef {:id (stock/entry-id entry)
                       :command command
                       :version version
                       :certname certname
                       :callback command-callback
                       :received (kitchensink/timestamp current-time)
                       :annotations {:id (stock/entry-id entry)
                                     :received (kitchensink/timestamp current-time)
                                     :attempts []}}))))

(defn ack-command
  [q command]
  (stock/discard q (:entry command)))

(deftype SortedCommandBuffer [^TreeMap fifo-queue ^HashMap certnames-map ^long max-entries]
  async-protos/Buffer
  (full? [this]
    (>= (.size fifo-queue) max-entries))

  (remove! [this]
    (let [^CommandRef cmdref (val (.pollFirstEntry fifo-queue))
          command-type (:command cmdref)]
      (when (or (= command-type "replace catalog")
                (= command-type "replace facts"))
        (.remove certnames-map [command-type (:certname cmdref)]))
      cmdref))

  (add!* [this item]
    (when-not (instance? CommandRef item)
      (throw (IllegalArgumentException. (str "Cannot enqueue item of type " (class item)))))

    (let [^CommandRef cmdref item
          command-type (:command cmdref)
          certname (:certname cmdref)]

      (when (or (= command-type "replace catalog")
                (= command-type "replace facts"))
        (when-let [^CommandRef old-command (.get certnames-map [command-type certname])]
          (.put fifo-queue
                (:id old-command)
                (assoc old-command :delete? true)))
        (.put certnames-map [command-type certname] cmdref))
      (.put fifo-queue (:id cmdref) cmdref))
    this)

  (close-buf! [this])
  clojure.lang.Counted
  (count [this]
    (.size fifo-queue)))

(defn sorted-command-buffer [^long n]
  (SortedCommandBuffer. (TreeMap.) (HashMap.) n))
