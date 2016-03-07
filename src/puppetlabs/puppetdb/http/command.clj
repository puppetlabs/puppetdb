(ns puppetlabs.puppetdb.http.command
  (:require [clojure.set :as set]
            [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.middleware :as mid]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.comidi :as cmdi]
            [ring.util.request :as request]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import [org.apache.commons.io IOUtils]
           [org.apache.commons.fileupload.util LimitedInputStream]))

(def min-supported-commands
  {;; Old commands
   "replace catalog" 6
   "replace facts" 4
   "store report" 5
   "deactivate node" 3

   ;; New commands
   "replace_catalog" 8
   "replace_facts" 5
   "store_report" 6
   "deactivate_node" 4})

(def valid-commands-str (str/join ", " (sort (vals command-names))))

(defn- validate-command-version
  [handle]
  (fn [{:keys [params] :as req}]
    (let [{:strs [command version]} params
          min-supported (min-supported-commands command)]
      (cond
        (not min-supported)
        (http/bad-request-response
         (format "Supported commands are %s. Received '%s'."
                 valid-commands-str command))

        (< version min-supported)
        (http/bad-request-response
         (format "%s version %s is retired. The minimum supported version is %s."
                 command version min-supported))

        :else (handle req)))))

(defmacro with-chan
  "Bind chan-sym to init-chan in the scope of the body, calling async/close! in
  a finally block.

  (with-chan [my-chan (clojure.core.async/chan)] ...)"
  [[chan-sym init-chan] & body]
  `(let [~chan-sym ~init-chan]
     (try
       (do ~@body)
       (finally (async/close! ~chan-sym)))))

(defmacro with-sub
  "Subscribe chan to the given 'from' pub, in the scope of the body."
  [{:keys [from topic chan]} & body]
  `(let [p# ~from, t# ~topic, c# ~chan]
     (try
       (async/sub p# t# c#)
       (do ~@body)
       (finally
         (async/unsub p# t# c#)))))

(defn- restrained-drained-stream [stream max-size]
  "Returns a stream that will throw ::body-stream-overflow and drain
  the rest of the stream if more than max-size-data is read."
  ;; The drain is because ruby.  i.e. if we closed the connection
  ;; without that, one of the ruby clients wouldn't handle the broken
  ;; pipe in a friendly way.
  (proxy [LimitedInputStream] [stream max-size]
    (raiseError [max-size count]
      ;; We don't trust skip; it appears to just invoke InputStream
      ;; skip which claims to allocate at least one buffer (of
      ;; unspecified size) per request.
      (loop [buf (byte-array (* 64 1024))]
        (when (pos? (.read ^java.io.InputStream this buf))
          (recur buf)))
      (throw+ ::body-stream-overflow))))

(defn- blocking-submit-command
  "Submit a command by calling do-submit-fn and block until it completes.
  Subscribes to response-pub on the topic of the commands uuid, waiting up to
  completion-timeout-ms."
  [do-submit-fn response-pub uuid completion-timeout-ms]
  (with-chan [response-chan (async/chan)]
    (with-sub {:from response-pub :topic (str uuid) :chan response-chan}
      (let [timeout-chan (async/timeout completion-timeout-ms)
            _ (do-submit-fn)]
        (async/alt!!
          timeout-chan (http/json-response {:uuid uuid
                                            :processed false
                                            :timed_out true}
                                           http/status-unavailable)
          response-chan ([{:keys [command exception]}]
                         (let [base-response {:uuid uuid
                                              :processed true}]
                           (if exception
                             (http/json-response
                              (assoc base-response
                                     :timed_out false
                                     :error (str exception)
                                     :stack_trace (map str (.getStackTrace exception)))
                              http/status-unavailable)
                             (http/json-response (assoc base-response
                                                        :timed_out false)
                                                 http/status-ok)))))))))

(def new-request-schema
  {:params {(s/required-key "command") s/Str
            (s/required-key "version") s/Str
            (s/required-key "certname") s/Str
            (s/required-key "received") s/Str
            (s/optional-key "secondsToWaitForCompletion") s/Str
            (s/optional-key "checksum") s/Str}
   :body java.io.InputStream
   s/Any s/Any})

(defn-validated normalize-new-request
  [req :- new-request-schema]
  (-> req
      (update-in [:params "version"] #(Integer/parseInt %))))

(def old-request-schema
  (s/conditional
   map?
   (s/pred #(empty? (select-keys (:params %)
                                 ["command" "version" "certname"])))))

(defn-validated ^:private normalize-old-request
  [{:keys [params body] :as req} :- old-request-schema]
  (log/warn (str "Unable to stream command posted without parameters"
                 " (loading into RAM)"))
  (if-not body
    (http/error-response "Empty application/json POST body")
    (let [body (json/parse-strict (:body req))]
      (if (empty? body)
        (http/error-response "Empty application/json POST body")
        (do
          (s/validate {(s/required-key "command") s/Str
                       (s/required-key "version") s/Int
                       (s/required-key "payload") {s/Any s/Any}}
                      body)
          (-> req
              (assoc :body (json/generate-string (body "payload")))
              (update :params
                      merge
                      (select-keys body ["command" "version"])
                      (some->> (get-in body ["payload" "certname"])
                               (hash-map "certname")))))))))

(defn- wrap-with-request-normalization
  "Converts request to the \"one true format\" if possible.  Ensures
  that the request :params include \"command\", \"version\", and maybe
  \"certname\" entries, and that the entries are in the correct
  format, i.e. spaces instead of underscores in the command name,
  integer instead of string for the version, etc.  Ensures that
  the :body only contains the \"payload\", as either a string or
  stream.  The :body will be a stream unless reading the body stream
  is unavoidable (i.e. old-style, non-param POST)."
  [handle]
  (fn [{:keys [params] :as req}]
    (handle (if (get params "command")
              (normalize-new-request req)
              (normalize-old-request req)))))

(defn- realize-body [body max-command-size]
  "Returns the body as an in-memory string or byte-array, reading it
  if necessary.  Throws ::body-stream-overflow if the max-command-size
  is not false and not respected."
  (if-not max-command-size
    (if (instance? java.io.InputStream body)
      (IOUtils/toByteArray body)
      body)
    (cond
      (instance? java.io.InputStream body)
      (IOUtils/toByteArray
       (restrained-drained-stream body (long max-command-size)))

      (string? body)
      ;; Given Java's UCS-2 encoding, incoming UTF-8 more or less
      ;; doubles in size when converted to a String.
      (if (> (count body) (* 2  max-command-size))
        (throw+ ::body-stream-overflow)
        body)

      :else
      (throw (Exception. (str "Unexpected body type: " (class body)))))))

(defn- enqueue-command-handler
  "Enqueues the command in request and returns a UUID"
  [enqueue-fn get-response-pub max-command-size]
  (fn [{:keys [body params] :as request}]
    ;; For now body will be in-memory, but eventually may be a stream.
    (try+
     (let [uuid (kitchensink/uuid)
           completion-timeout-ms (some-> params
                                         (get "secondsToWaitForCompletion")
                                         Double/parseDouble
                                         (* 1000))
           submit-params (select-keys params ["certname" "command" "version"])
           submit-params (if-let [v (submit-params "version")]
                           (update submit-params "version" str)
                           submit-params)
           ;; Replace read-body when our queue supports streaming
           do-submit #(enqueue-fn (realize-body body max-command-size)
                                  uuid
                                  submit-params)]
       (if (some-> completion-timeout-ms pos?)
         (blocking-submit-command do-submit (get-response-pub)
                                  uuid
                                  completion-timeout-ms)
         (do
           (do-submit)
           (http/json-response {:uuid uuid}))))
     (catch (= ::body-stream-overflow %) _
       (http/error-response "Command size exceeds max-command-size"
                            http/status-entity-too-large)))))

(defn- add-received-param
  [handle]
  (fn [req]
    (handle (assoc-in req [:params "received"] (kitchensink/timestamp)))))

(defn routes [enqueue-fn get-response-pub max-command-size]
  (cmdi/context "/v1"
                (cmdi/ANY "" []
                          (enqueue-command-handler enqueue-fn get-response-pub
                                                   max-command-size))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a ring handler and
;; return functions that accept a ring request map

(defn command-app
  [get-shared-globals enqueue-fn get-response-pub
   reject-large-commands? max-command-size]
  (-> (routes enqueue-fn get-response-pub
              (when reject-large-commands? max-command-size))
      mid/make-pdb-handler
      validate-command-version
      wrap-with-request-normalization
      add-received-param ;; must be (temporally) after validate-query-params
      ;; The checksum here is vestigial.  It is no longer checked
      (mid/validate-query-params {:optional ["checksum" "secondsToWaitForCompletion"
                                             "certname" "command" "version"]})
      mid/verify-accepts-json
      (mid/verify-content-type ["application/json"])
      (mid/fail-when-payload-too-large reject-large-commands? max-command-size)
      (mid/wrap-with-metrics (atom {}) http/leading-uris)
      (mid/wrap-with-globals get-shared-globals)))
