(ns puppetlabs.puppetdb.middleware
  "Ring middleware"
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng :as qe]
            [puppetlabs.puppetdb.utils.metrics :refer [multitime!]]
            [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [ring.util.request :as request]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [clojure.set :as set]
            [pantomime.media :as media]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [metrics.timers :refer [timer time!]]
            [metrics.meters :refer [meter mark!]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.utils :as utils]
            [bidi.bidi :as bidi]
            [bidi.ring :as bring]
            [bidi.schema :as bidi-schema]
            [puppetlabs.puppetdb.schema :as pls]
            [schema.core :as s]))

(def handler-schema (s/=> s/Any {s/Any s/Any}))

(defn wrap-with-debug-logging
  "Ring middleware that logs incoming HTTP request URIs (at DEBUG level) as
  requests come in.  To enable, add this line to your logback.xml:

  `<logger name=\"puppetlabs.puppetdb.middleware\" level=\"debug\"/>`"
  [app]
  (fn [req]
    (log/debug (str "Processing HTTP request to URI: '" (:uri req) "'"))
    (app req)))

(defn build-whitelist-authorizer
  "Build a function that will authorize requests based on the supplied
  certificate whitelist (see `cn-whitelist->authorizer` for more
  details). Returns :authorized if the request is allowed, otherwise a
  string describing the reason not."
  [whitelist]
  {:pre  [(string? whitelist)]
   :post [(fn? %)]}
  (let [allowed? (kitchensink/cn-whitelist->authorizer whitelist)]
    (fn [{:keys [ssl-client-cn] :as req}]
      (if (allowed? req)
        :authorized
        (do
          (log/warnf "%s rejected by certificate whitelist %s" ssl-client-cn whitelist)
          (format (str "The client certificate name (%s) doesn't "
                       "appear in the certificate whitelist. Is your "
                       "master's (or other PuppetDB client's) certname "
                       "listed in PuppetDB's certificate-whitelist file?")
                  ssl-client-cn))))))

(defn wrap-with-authorization
  "Ring middleware that will only pass through a request if the
  supplied authorization function allows it. Otherwise an HTTP 403 is
  returned to the client.  If get-authorizer is nil or false, all
  requests will be accepted.  Otherwise it must accept no arguments
  and return an authorize function that accepts a request.  The
  request will be allowed only if authorize returns :authorized.
  Otherwise, the return value should be a message describing the
  reason that access was denied."
  [app cert-whitelist]
  (let [authorize (and cert-whitelist (build-whitelist-authorizer cert-whitelist))]
    (if-not authorize
      app
      (fn [req]
        (let [auth-result (authorize req)]
          (if (= :authorized auth-result)
            (app req)
            (-> (str "Permission denied: " auth-result)
                (rr/response)
                (rr/status http/status-forbidden))))))))

(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [app]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (if ssl-client-cert
                (kitchensink/cn-for-cert ssl-client-cert))
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

(defn wrap-pretty-printing-opts
  [app]
  (fn [{:keys [params] :as req}]
    (if-let [pretty (get params "pretty")]
      (if-not (or (= "false" pretty)
                  (= "true" pretty))
        (http/error-response (str "Parameter 'pretty' must be either 'true' or 'false' not '"
                                  pretty
                                  "'"))
        (let [new-req (-> req
                          (update :params dissoc "pretty")
                          (assoc-in [:globals :pretty-print] (= "true" pretty)))]
          (app new-req)))
      (app req))))

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

(defn verify-accepts-content-type
  "Ring middleware that requires a request for the wrapped `app` to accept the
  provided `content-type`. If the content type isn't acceptable, a 406 Not
  Acceptable status is returned, with a message informing the client it must
  accept the content type."
  [app content-type]
  {:pre (string? content-type)}
  (fn [{:keys [headers] :as req}]
    (if (http/acceptable-content-type
         content-type
         (headers "accept"))
      (app req)
      (rr/status (rr/response (str "must accept " content-type))
                 http/status-not-acceptable))))

(defn verify-content-type
  "Verification for the specified list of content-types."
  [app content-types]
  {:pre [(coll? content-types)
         (every? string? content-types)]}
  (fn [{:keys [headers] :as req}]
    (let [content-type (headers "content-type")
          mediatype (if (nil? content-type) nil
                        (str (media/base-type content-type)))]
      (if (or (nil? mediatype) (some #{mediatype} content-types))
        (app req)
        (rr/status (rr/response (str "content type " mediatype " not supported"))
                   http/status-unsupported-type)))))

(defn validate-query-params
  "Ring middleware that verifies that the query params in the request
  are legal based on the map `param-specs`, which contains a list of
  `:required` and `:optional` query parameters.  If the validation fails,
  a 400 Bad Request is returned, with an explanation of the invalid
  parameters."
  [app param-specs]
  {:pre [(map? param-specs)
         (= #{} (kitchensink/keyset (dissoc param-specs :required :optional)))
         (every? string? (:required param-specs))
         (every? string? (:optional param-specs))]}
  (fn [{:keys [params] :as req}]
    (kitchensink/cond-let [p]
                          (kitchensink/excludes-some params (:required param-specs))
                          (http/error-response (str "Missing required query parameter '" p "'"))

                          (let [diff (set/difference (kitchensink/keyset params)
                                                     (set (:required param-specs))
                                                     (set (:optional param-specs)))]
                            (seq diff))
                          (http/error-response (str "Unsupported query parameter '" (first p) "'"))

                          :else
                          (app req))))

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
      (let [length-in-bytes (request/content-length req)]

        (if length-in-bytes
          (log/debugf "Processing command with a content-length of %s bytes" length-in-bytes)
          (log/warn (str "No content length found for POST. "
                         "POST bodies that are too large could cause memory-related failures.")))

        (if (and length-in-bytes
                 reject-large-commands?
                 (> length-in-bytes max-command-size))
          (do
            (log/warnf "content-length of command is %s bytes and is larger than the maximum allowed command size of %s bytes"
                       length-in-bytes
                       max-command-size)
            (consume-and-close (:body req) length-in-bytes)
            (http/error-response
             "Command rejected due to size exceeding max-command-size"
             http/status-entity-too-large))
          (app req)))
      (app req))))

(defn wrap-with-puppetdb-middleware
  "Default middleware for puppetdb webservers."
  [app cert-whitelist]
  (-> app
      wrap-pretty-printing-opts
      wrap-params
      (wrap-with-authorization cert-whitelist)
      wrap-with-certificate-cn
      wrap-with-default-body
      wrap-with-debug-logging))

(defn parent-check
  "Middleware that checks the parent exists before serving the rest of the
   application. This ensures we always return 404's on child paths when the
   parent data is empty."
  [app version parent route-param-key]
  (fn [{:keys [globals route-params] :as req}]
    (let [{:keys [scf-read-db url-prefix]} globals]
      ;; There is a race condition here, in particular we open up 1 transaction
      ;; for the parent test, but the rest of the query results are done via the
      ;; streaming query. This can't be solved until we work out a way to
      ;; pass through an existing db handle through to the streamed query thread.
      (if (jdbc/with-transacted-connection scf-read-db
            (qe/object-exists? parent (get route-params route-param-key)))
        (app req)
        (http/json-response {:error (str "No information is known about " (name parent) " " (get route-params route-param-key))} http/status-not-found)))))

(pls/defn-validated url-decode :- s/Str
  [x :- s/Str]
  (java.net.URLDecoder/decode x))

(pls/defn-validated make-pdb-handler :- handler-schema
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

