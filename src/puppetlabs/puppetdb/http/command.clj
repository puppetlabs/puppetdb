(ns puppetlabs.puppetdb.http.command
  (:require [clojure.set :as set]
            [puppetlabs.puppetdb.command.constants :refer [command-names
                                                           normalize-command-name]]
            [puppetlabs.puppetdb.utils :as utils
             :refer [content-encoding->file-extension]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.middleware :as mid]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.comidi :as cmdi]
            [puppetlabs.i18n.core :refer [trs tru]]
            [puppetlabs.ring-middleware.core :as rmc])
  (:import
   (clojure.lang ExceptionInfo)
   (java.net HttpURLConnection)
   (org.apache.commons.fileupload.util LimitedInputStream)))

(def min-supported-commands
  {"configure expiration" 1
   "replace catalog" 6
   "replace catalog inputs" 1
   "replace facts" 4
   "store report" 5
   "deactivate node" 3})

(def valid-commands-str (str/join ", " (sort (vals command-names))))

(defn- wrap-with-request-params-validation
  "Validates the request parameters in the :params request map. This middleware
  should ingest the request after request normalization middleware."
  [handler]
  (fn validate-request-params
    [{:keys [params] :as request}]
    (let [{:strs [command version certname]} params
          request-params (set (keys params))
          ;; The checksum here is vestigial. It is no longer checked
          valid-params #{"checksum" "secondsToWaitForCompletion" "certname"
                         "command" "version" "producer-timestamp"}
          required-params #{"certname" "version" "command"}
          invalid-params (seq (set/difference request-params valid-params))
          missing-params (seq (set/difference required-params request-params))
          min-supported-version (min-supported-commands command)]
      (cond
       missing-params
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Command is missing required parameters: {0}."
                        (str/join ", " missing-params))]))

       invalid-params
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Command has invalid parameters: {0}."
                        (str/join ", " invalid-params))]))

       (or (not (string? certname)) (str/blank? certname))
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Certname must be a non-empty string.")]))

       ;; Verify command is valid by checking to see if it was found in the
       ;; min-supported-command map
       (nil? min-supported-version)
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Command must be one of: {0}." valid-commands-str)]))

       (not (int? version))
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Version must be a valid integer.")]))

       (< version min-supported-version)
       (http/bad-request-response
        (str/join " "
                  [(tru "Command {0} for certname {1} is invalid."
                        (pr-str command) (pr-str certname))
                   (tru "Version {0} of command {1} is retired."
                        version (pr-str command))
                   (tru "The minimum supported version is {0}." min-supported-version)]))

       :else (handler request)))))

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

(defn- restrained-drained-stream
  "Returns a stream that will throw an ex-info exception
  of :kind ::body-stream-overflow and drain the rest of the stream if
  more than max-size-data is read."
  [stream max-size]
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
      (throw (ex-info "" {:kind ::body-stream-overflow})))))

(defn- blocking-submit-command
  "Submit a command by calling do-submit-fn and block until it completes.
  Waiting up to completion-timeout-ms if provided."
  [do-submit-fn uuid completion-timeout-ms]
  (let [command-result (promise)]
    (do-submit-fn (fn [the-result]
                    (deliver command-result the-result)))
    (let [result (deref command-result completion-timeout-ms ::timeout)]
      (if (= ::timeout result)
        (http/json-response {:uuid uuid
                             :processed false
                             :timed_out true}
                            HttpURLConnection/HTTP_UNAVAILABLE)
        (let [{:keys [exception]} result
              base-response {:uuid uuid
                             :processed true}]
          (if exception
            (http/json-response
             (assoc base-response
                    :timed_out false
                    :error (str exception)
                    :stack_trace (map str (.getStackTrace exception)))
             HttpURLConnection/HTTP_UNAVAILABLE)
            (http/json-response (assoc base-response
                                       :timed_out false)
                                HttpURLConnection/HTTP_OK)))))))

(defn remove-nil-params
  "Removes key-value pairs in the request :params map when value is nil"
  [request]
  (update request
          :params
          (fn [m] (apply dissoc m (for [[k v] m :when (nil? v)] k)))))

(defn- wrap-with-request-normalization
  "Converts request to the \"one true format\" if possible. The \"new\" format
  is sending the command, version, and certname as query parameters and the
  payload as the JSON body. The \"old\" format is sending the command, version,
  and payload as top-level keys in the JSON body. This middleware transforms
  the \"old\" format by taking the command, version, and certname from the JSON
  body and inserting them into the :params map. It transforms the \"new\"
  format by replacing underscores with spaces in the command name and by
  attempting to parse the version value into an integer."
  [handler]
  (fn normalize-request [{:keys [params body] :as req}]
    (if (params "command")
      ;; Request has "command" param. This is the new format.
      (let [try-parse-version #(try (Integer/valueOf %)
                                 (catch NumberFormatException _ %))]
        (-> req
            (update-in [:params "command"] normalize-command-name)
            (update-in [:params "version"] try-parse-version)
            handler))
      ;; Request does not have "command" param. This is the old format.
      (do
        (log/warn (trs "Unable to stream command posted without parameters (loading into RAM)"))
        (let [{:strs [command version payload] :as decoded-body}
              (try (json/parse-strict body)
                (catch Exception _ {}))
              has-required-keys? (and command version payload)
              certname (get payload "certname")
              new-params (-> decoded-body
                             (dissoc "payload")
                             (assoc "certname" certname))]
          (cond
           (not has-required-keys?)
           (http/bad-request-response
            (str/join " "
                      [(tru "Command {0} for certname {1} is invalid."
                            (pr-str command) (pr-str certname))
                       (tru "Command was submitted without query parameters (old format).")
                       (tru "The request body must be a JSON map with required keys: {0}."
                            "command, version, payload")]))

           (not (map? payload))
           (http/bad-request-response
            (str/join " "
                      [(tru "Command {0} for certname {1} is invalid."
                            (pr-str command) (pr-str certname))
                       (tru "Command was submitted without query parameters (old format).")
                       (tru "The payload value must be a JSON map.")]))

           :else
           (-> req
               (assoc :body (json/generate-string payload))
               (assoc :params new-params)
               remove-nil-params
               handler)))))))

(defn- stream-with-max-check
  "Returns the body as an in-memory string or byte-array, reading it if
  necessary.  Throws an ex-info exception
  of :kind ::body-stream-overflow if the max-command-size is not false
  and not respected."
  [body max-command-size]
  (if-not max-command-size
    (cond
      (instance? java.io.InputStream body)
      body

      (string? body)
      (java.io.ByteArrayInputStream. (.getBytes body "UTF-8"))

      :else
      (throw (Exception. (tru "Unexpected body type: {0}" (class body)))))
    (cond
      (instance? java.io.InputStream body)
      (restrained-drained-stream body (long max-command-size))

      (string? body)
      ;; Given Java's UCS-2 encoding, incoming UTF-8 more or less
      ;; doubles in size when converted to a String.
      (if (> (* 2 (count body)) max-command-size)
        (throw (ex-info "" {:kind ::body-stream-overflow}))
        (java.io.ByteArrayInputStream. (.getBytes body "UTF-8")))

      :else
      (throw (Exception. (tru "Unexpected body type: {0}" (class body)))))))

(defn- enqueue-command-handler
  "Enqueues the command in request and returns a UUID"
  [enqueue-fn max-command-size]
  (fn [{:keys [body params headers]}]
    ;; For now body will be in-memory, but eventually may be a stream.
    (try
      (let [uuid (kitchensink/uuid)
            completion-timeout-ms (some-> params
                                          (get "secondsToWaitForCompletion")
                                          Double/parseDouble
                                          (* 1000))
            submit-params (select-keys params ["certname" "command" "version" "producer-timestamp"])
            submit-params (if (submit-params "version")
                            (update submit-params "version" str)
                            submit-params)
            compression (content-encoding->file-extension
                         (get headers "content-encoding"))
            ;; Replace read-body when our queue supports streaming
            do-submit (fn [command-callback]
                        (enqueue-fn
                         (get submit-params "command")
                         (Integer/parseInt (get submit-params "version"))
                         (get submit-params "certname")
                         (get submit-params "producer-timestamp")
                         (stream-with-max-check body max-command-size)
                         compression
                         command-callback))]

        (if (some-> completion-timeout-ms pos?)
          (blocking-submit-command do-submit
                                   uuid
                                   completion-timeout-ms)
          (do
            (do-submit identity)
            (http/json-response {:uuid (kitchensink/uuid)}))))
      (catch ExceptionInfo ex
        (when-not (= ::body-stream-overflow (:kind (ex-data ex)))
          (throw ex))
        (http/error-response (tru "Command size exceeds max-command-size")
                             HttpURLConnection/HTTP_ENTITY_TOO_LARGE)))))

(defn- add-received-param
  [handle]
  (fn [req]
    (handle (assoc-in req [:params "received"] (kitchensink/timestamp)))))

(defn routes [enqueue-fn max-command-size]
  (cmdi/context "/v1"
                (cmdi/ANY "" []
                          (enqueue-command-handler enqueue-fn max-command-size))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a ring handler and
;; return functions that accept a ring request map

(defn command-app
  [get-shared-globals enqueue-fn reject-large-commands? max-command-size]
  (-> (routes enqueue-fn
              (when reject-large-commands? max-command-size))
      mid/make-pdb-handler
      add-received-param ;; must be (temporally) after wrap-with-request-params-validation
      wrap-with-request-params-validation
      wrap-with-request-normalization
      rmc/wrap-accepts-json
      (rmc/wrap-content-type ["application/json"])
      (mid/verify-content-encoding utils/supported-content-encodings)
      (mid/fail-when-payload-too-large reject-large-commands? max-command-size)
      (mid/wrap-with-metrics (atom {}) http/leading-uris)
      (mid/wrap-with-globals get-shared-globals)
      mid/wrap-with-exception-handling))
