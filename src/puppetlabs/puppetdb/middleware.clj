(ns puppetlabs.puppetdb.middleware
  "Ring middleware"
  (:require [puppetlabs.i18n.core :as i18n :refer [trs tru]]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng :as qe]
            [puppetlabs.puppetdb.utils.metrics :refer [multitime!]]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.schema :refer [defn-validated]]
            [ring.util.request :as request]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.middleware.params :refer [wrap-params]]
            [clojure.set :as set]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [metrics.timers :refer [timer]]
            [metrics.meters :refer [meter mark!]]
            [metrics.histograms :refer [update!]]
            [bidi.bidi :as bidi]
            [bidi.ring :as bring]
            [bidi.schema :as bidi-schema]
            [schema.core :as s]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.puppetdb.constants :as constants]
            [puppetlabs.puppetdb.command.constants :as const])
  (:import
   (java.net HttpURLConnection)))

(def handler-schema (s/=> s/Any {s/Any s/Any}))

(defn wrap-with-debug-logging
  "Ring middleware that logs incoming HTTP request URIs (at DEBUG level) as
  requests come in.  To enable, add this line to your logback.xml:

  `<logger name=\"puppetlabs.puppetdb.middleware\" level=\"debug\"/>`"
  [app]
  (fn [req]
    (log/debug (trs "Processing HTTP request to URI: ''{0}''" (:uri req)))
    (app req)))

(defn build-allowlist-authorizer
  "Build a function that will authorize requests based on the supplied
  certificate allowlist (see `cn-whitelist->authorizer` for more
  details). Returns :authorized if the request is allowed, otherwise a
  string describing the reason not."
  [allowlist]
  {:pre  [(string? allowlist)]
   :post [(fn? %)]}
  (let [allowed? (kitchensink/cn-whitelist->authorizer allowlist)]
    (fn [{:keys [ssl-client-cn] :as req}]
      (when-not (allowed? req)
        (when ssl-client-cn
          (log/warn (trs "{0} rejected by certificate allowlist {1}" ssl-client-cn allowlist)))
        (http/denied-response (tru "The client certificate name {0} doesn't appear in the certificate allowlist. Is your master''s (or other PuppetDB client''s) certname listed in PuppetDB''s certificate-allowlist file?" ssl-client-cn)
                              HttpURLConnection/HTTP_FORBIDDEN)))))

(defn wrap-cert-authn
  [app cert-allowlist]
  (if-let [cert-authorize-fn (some-> cert-allowlist build-allowlist-authorizer)]
    (fn [req]
      (if-let [cert-auth-result (cert-authorize-fn req)]
        cert-auth-result
        (app req)))
    app))

(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [app]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (some-> ssl-client-cert kitchensink/cn-for-cert)
          req (assoc req :ssl-client-cn cn)]
      (app req))))

(defn wrap-with-default-body
  "Ring middleware that will attach a default body based on the response code
  if no other body is supplied."
  [app]
  (fn [req]
    (let [{:keys [body] :as response} (app req)]
      (if body
        response
        (assoc response :body (http/default-body req response))))))

(defn wrap-with-globals
  "Ring middleware that adds a :globals attribute to each request that
   contains a map of the current shared-global settings."
  [app get-shared-globals]
  (fn [req]
    (let [new-req (update req :globals #(merge (get-shared-globals) %))]
      (app new-req))))

(defn wrap-with-illegal-argument-catch
  [app]
  (fn [req]
    (try
      (app req)
      (catch IllegalArgumentException e
        (http/error-response e)))))

(defn cause-finder
  [ex]
  (let [cause (.getCause ex)]
    (if (nil? cause)
      (.getMessage ex)
      (recur cause))))

(defn wrap-with-exception-handling
  [app]
  (fn [req]
    (try
      (app req)
      (catch Exception e
        (log/error e)
        (http/error-response (cause-finder e)
                             HttpURLConnection/HTTP_INTERNAL_ERROR))
     (catch AssertionError e
        (log/error e)
        (http/error-response (tru "An unexpected error occurred while processing the request")
                             HttpURLConnection/HTTP_INTERNAL_ERROR))
     (catch Throwable e
        (log/error e)
        (http/error-response (tru "An unexpected error occurred")
                             HttpURLConnection/HTTP_INTERNAL_ERROR)))))

(defn verify-accepts-content-type
  "Ring middleware that requires a request for the wrapped `app` to accept the
  provided `content-type`. If the content type isn't acceptable, a 406 Not
  Acceptable status is returned, with a message informing the client it must
  accept the content type."
  [app content-type]
  {:pre [(string? content-type)]}
  (fn [{:keys [headers] :as req}]
    (if (http/acceptable-content-type
         content-type
         (headers "accept"))
      (app req)
      (http/error-response (tru "must accept {0}" content-type)
                           HttpURLConnection/HTTP_NOT_ACCEPTABLE))))

(defn verify-content-encoding
  "Verification for the specified list of content-encodings."
  [app allowed-encodings]
  {:pre [(coll? allowed-encodings)
         (every? string? allowed-encodings)]}
  (fn [{:keys [headers request-method] :as req}]
    (let [content-encoding (headers "content-encoding")]
      (if (or (not= request-method :post)
              (empty? content-encoding)
              (some #{content-encoding} allowed-encodings))
        (app req)
        (http/error-response (tru "content encoding {0} not supported"
                                  content-encoding)
                             HttpURLConnection/HTTP_UNSUPPORTED_TYPE)))))

(defn verify-content-type
  "Verification for the specified list of content-types."
  [app content-types]
  {:pre [(coll? content-types)
         (every? string? content-types)]}
  (fn [{:keys [headers] :as req}]
    (if (= (:request-method req) :post)
      (let [content-type (headers "content-type")
            mediatype (if (nil? content-type) nil
                          (kitchensink/base-type content-type))]
        (if (or (nil? mediatype) (some #{mediatype} content-types))
          (app req)
          (http/error-response (tru "content type {0} not supported" mediatype)
                               HttpURLConnection/HTTP_UNSUPPORTED_TYPE)))
      (app req))))

(def params-schema {(s/optional-key :optional) [s/Str]
                    (s/optional-key :required) [s/Str]})

(defn-validated validate-query-params
  "Ring middleware that verifies that the query params in the request are legal
  based on the map `param-specs`, which contains a list of `:required` and
  `:optional` query parameters. If the validation fails, a 400 Bad Request is
  returned, with an explanation of the invalid parameters."
  [app param-specs :- params-schema]
  (fn [{:keys [params] :as req}]
    (kitchensink/cond-let [p]
                          (kitchensink/excludes-some params (:required param-specs))
                          (http/error-response (tru "Missing required query parameter ''{0}''" p))

                          (let [diff (set/difference (kitchensink/keyset params)
                                                     (set (:required param-specs))
                                                     (set (:optional param-specs)))]
                            (seq diff))
                          (http/error-response (tru "Unsupported query parameter ''{0}''" (first p)))

                          :else
                          (app req))))

(defn merge-param-specs
  [& specs]
  (letfn [(assoc-distinct-vals [result key & maps]
            (cond-> result
              (some key maps) (assoc key (distinct (mapcat key maps)))))]
    (reduce (fn [result spec]
              (-> (merge result spec)
                  (assoc-distinct-vals :required result spec)
                  (assoc-distinct-vals :optional result spec)))
            nil
            specs)))

(defn validate-no-query-params
  "Ring middleware that verifies that there are no query params on the request.
  Convenience method for endpoints that do not support any query params.  If the
  validation fails, a 400 Bad Request is returned, with an explanation of the
  invalid parameters."
  [app]
  (validate-query-params app {}))

(def verify-accepts-json
  "Ring middleware which requires a request for `app` to accept
  application/json as a content type. If the request doesn't accept
  application/json, a 406 Not Acceptable status is returned with an error
  message indicating the problem."
  (fn [app]
    (verify-accepts-content-type app "application/json")))

(def http-metrics-registry (get-in metrics/metrics-registries [:http :registry]))

(defn wrap-with-metrics
  "Ring middleware that will tack performance counters for each URL.
  We track the following metrics per-app, at a top-level

  * `service-time`: how long it took to service the request

  We track the following meters per-app, and per-response-code:

  * `reqs/s`: the rate at which responses of a given status are
    reported

  Created metrics are stored in the supplied `storage` atom with the
  following structure:

      {:timers {<normalized uri> <service time metric>
                <normalized uri> <service time metric>}
       :meters {<normalized uri> {<status code> <reqs/s>
                                  <status code> <reqs/s>}}}

  `app`: The ring app to be wrapped

  `storage`: An atom that will be used to hold references to all
  created metrics.

  `normalize-uri`: A function that takes a URI, and returns a string
  or collection of strings. For each string returned, a timer and
  meter will be created if they don't already exist. To have multiple
  URLs share the same timer/meter, `normalize-uri` should return some
  of the same strings for each URL.

  Metric names (and thus, the strings returned by `normalize-uri`
  cannot contain ':', '=', or ',' characters. They will be replaced
  with '_'."
  [app storage normalize-uri]
  (fn [req]
    ;; add metric timers for the uri as we service the request.
    (let [metric-roots (let [s (normalize-uri (:uri req))]
                         (if (string? s) [s] s))
          metric-roots (map #(str/replace % #"[:,=]" "_") metric-roots)]

      ;; Create timer objects for each metric the user has requested
      (doseq [metric-root metric-roots
              :let [timer-key [:timers metric-root]]
              :when (not (get-in @storage timer-key))]
        (swap! storage assoc-in timer-key (timer http-metrics-registry [metric-root "service-time"])))

      (let [timers (map #(get-in @storage [:timers %]) metric-roots)]
        (multitime! timers
                    (let [response  (app req)
                          status    (:status response)]

                      ;; Create meter objects for each metric the user has
                      ;; requested
                      (doseq [metric-root metric-roots
                              :let [meter-key [:meters metric-root status]]
                              :when (not (get-in @storage meter-key))]
                        (swap! storage assoc-in meter-key (meter http-metrics-registry [metric-root (str status)]))
                        (mark! (get-in @storage meter-key)))

                        response))))))

(defn consume-and-close
  "Consume all data from input stream and then close"
  [^java.io.InputStream req-stream content-length]
  (with-open [s req-stream]
    (loop []
      (when-not (= -1 (.read s))
        (.skip s content-length)
        (recur)))))

(defn fail-when-payload-too-large
  "Middlware that will return a 413 failure when the content-length of
  a POST is too large (more than `max-command-size`). Acts as a noop
  when it is less or `reject-large-commands? is false"
  [app reject-large-commands? max-command-size]
  (fn [req]
    (if (= :post (:request-method req))
      (let [length-in-bytes (or (when-let [length (get-in req [:headers "x-uncompressed-length"])]
                                  (try
                                    (Long/parseLong length)
                                    (catch NumberFormatException _
                                      (log/warn (trs "The X-Uncompressed-Length value {0} cannot be converted to a long."
                                                     length)))))
                                (request/content-length req))]

        (let [{:strs [command version]} (:query-params req)]
          (if length-in-bytes
            (do (log/debug (trs "Processing command with a content-length of {0} bytes" length-in-bytes))
                (update! (cmd/global-metric :size) length-in-bytes)
                (when (and command version)
                  (let [command (const/normalize-command-name command)]
                    ;; command name must be normalized so correct metrics are updated
                    (cmd/create-metrics-for-command! command version)
                    (update! (cmd/cmd-metric command version :size) length-in-bytes))))
            (log/warn (trs "Neither Content-Length or X-Uncompressed-Length header is set.
                            This {0} command will not be counted in command size metrics"
                           command))))

        (if (and length-in-bytes
                 reject-large-commands?
                 (> length-in-bytes max-command-size))
          (do
            (log/warn (trs "content-length of command is {0} bytes and is larger than the maximum allowed command size of {1} bytes"
                           length-in-bytes
                           max-command-size))
            (consume-and-close (:body req) length-in-bytes)
            (http/error-response
             (tru "Command rejected due to size exceeding max-command-size")
             HttpURLConnection/HTTP_ENTITY_TOO_LARGE))
          (app req)))
      (app req))))

;; for testing via with-redefs
(defn get-sync-ver []
  constants/pdb-sync-ver)

(defn verify-sync-version
  "Check that the x-pdb-sync-ver header of an incoming requests matches the
   pdb-sync-ver locally. This header is only sent from pe-puppetdb sync requests
   and a mismatch indicates that one pdb has upgraded in an incompatible way
   before the other. In this case all sync requests will return 409s until both
   sides have been upgraded and have matching sync versions. If the header is
   present but improperly formatted returns a 400 error response."
  [app]
  (fn [{{:strs [x-pdb-sync-ver]} :headers :as req}]
    (let [maybe-sync-ver (try
                           (some-> x-pdb-sync-ver Integer/parseInt)
                           (catch NumberFormatException _
                             {:error true :input x-pdb-sync-ver}))]
      (cond
        (nil? maybe-sync-ver) (app req)

        (integer? maybe-sync-ver)
        (if (= (get-sync-ver) maybe-sync-ver)
          (app req)
          (http/error-response
           (tru "Conflicting PDB sync versions, each PDB syncing must be on the same version")
           409))

        (:error maybe-sync-ver)
        (http/error-response
         (tru "The x-pdb-sync-ver header: {0} cannot be converted to an int."
              (:input maybe-sync-ver)))

        :else (http/error-response
               (tru "Unknown sync version check state")
               500)))))

(defn wrap-with-puppetdb-middleware
  "Default middleware for puppetdb webservers."
  [app]
  (-> app
      wrap-params
      wrap-with-certificate-cn
      wrap-with-default-body
      wrap-with-debug-logging
      i18n/locale-negotiator))

(defn parent-check
  "Middleware that checks the parent exists before serving the rest of the
   application. This ensures we always return 404's on child paths when the
   parent data is empty."
  [app _version parent route-param-key]
  (fn [{:keys [globals route-params] :as req}]
    (let [{:keys [scf-read-db]} globals]
      ;; There is a race condition here, in particular we open up 1 transaction
      ;; for the parent test, but the rest of the query results are done via the
      ;; streaming query. This can't be solved until we work out a way to
      ;; pass through an existing db handle through to the streamed query thread.
      (if (jdbc/with-transacted-connection scf-read-db
            (qe/object-exists? parent (get route-params route-param-key)))
        (app req)
        (http/json-response {:error (tru "No information is known about {0} {1}"
                                         (name parent)
                                         (get route-params route-param-key))}
                            HttpURLConnection/HTTP_NOT_FOUND)))))

(defn-validated url-decode :- s/Str
  [x :- s/Str]
  (java.net.URLDecoder/decode x "utf-8"))

(defn-validated make-pdb-handler :- handler-schema
  "Similar to `bidi.ring/make-handler` but does not merge route-params
  into the regular parameter map. Currently route-params causes
  validation errors with merged in with parameters. Parameter names
  are currently strings and validated against an expected list. Route
  params are merged in a keywords."
  ([route :- bidi-schema/RoutePair]
   (make-pdb-handler route identity))
  ([route :- bidi-schema/RoutePair
    handler-fn :- handler-schema]
   (fn [{:keys [uri path-info] :as req}]
     (let [path (or path-info uri)
           {:keys [handler route-params] :as match-context} (bidi/match-route* route path req)]
       (when handler
         (bring/request
          (handler-fn handler)
          (update req :route-params merge (kitchensink/mapvals url-decode route-params))
          (apply dissoc match-context :handler (keys req))))))))
