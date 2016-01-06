(ns puppetlabs.puppetdb.middleware
  "Ring middleware"
  (:require [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.query-eng :as qe]
            [puppetlabs.puppetdb.utils.metrics :refer [multitime!]]
            [puppetlabs.puppetdb.http :as http]
            [ring.util.response :as rr]
            [ring.util.request :as request]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [ring.middleware.params :refer [wrap-params]]
            [puppetlabs.puppetdb.query.paging :as paging]
            [clojure.set :as set]
            [pantomime.media :as media]
            [puppetlabs.puppetdb.jdbc :refer [with-transacted-connection]]
            [metrics.timers :refer [timer time!]]
            [metrics.meters :refer [meter mark!]]
            [clojure.walk :refer [keywordize-keys]]
            [ring.middleware.multipart-params :as mp]
            [puppetlabs.puppetdb.utils :as utils]))

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
    (let [pretty (get params "pretty" "false")]
      (if-not (or (= "false" pretty)
                  (= "true" pretty))
        (http/error-response (str "Parameter 'pretty' must be either 'true' or 'false' not '"
                                  pretty
                                  "'"))
        (let [new-req (-> req
                          (update :params dissoc "pretty")
                          (assoc-in [:globals :pretty-print] (= "true" pretty)))]
          (app new-req))))))

(defn wrap-with-globals
  "Ring middleware that adds a :globals attribute to each request that
  contains a map of the current shared-global settings."
  [app get-shared-globals]
  (fn [req]
    (let [new-req (update req :globals merge (get-shared-globals))]
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

(defn verify-checksum
  "Ring middleware which will verify that the content of the body-string
  has the checksum specified in the `checksum` parameter. If no checksum is
  provided, this check will be skipped. If the checksum doesn't match, a 400
  Bad Request error is returned."
  [app]
  (fn [{:keys [params body-string] :as req}]
    (let [expected-checksum (params "checksum")
          payload           body-string]
      (if (and expected-checksum
               (not= expected-checksum (kitchensink/utf8-string->sha1 payload)))
        (http/error-response "checksums don't match")
        (app req)))))

(defn wrap-with-metrics*
  "Ring middleware that will tack performance counters for each
  URL. Arguments are the same as for `wrap-with-metrics`, except:

  `prefix`: string to use as the first component of each generated
  metric."
  [app prefix storage normalize-uri]
  (fn [req]
    ;; add metric timers for the uri as we service the request.
    (let [metric-roots (let [s (normalize-uri (:uri req))]
                         (if (string? s) [s] s))
          metric-roots (map #(s/replace % #"[:,=]" "_") metric-roots)]

      ;; Create timer objects for each metric the user has requested
      (doseq [metric-root metric-roots
              :let [timer-key [:timers metric-root]]
              :when (not (get-in @storage timer-key))]
        (swap! storage assoc-in timer-key (timer [prefix metric-root "service-time"])))

      (let [timers (map #(get-in @storage [:timers %]) metric-roots)]
        (multitime! timers
                    (let [response  (app req)
                          status    (:status response)]

                      ;; Create meter objects for each metric the user has
                      ;; requested
                      (doseq [metric-root metric-roots
                              :let [meter-key [:meters metric-root status]]
                              :when (not (get-in @storage meter-key))]
                        (swap! storage assoc-in meter-key (meter [prefix metric-root (str status)] "reqs/s"))
                        (mark! (get-in @storage meter-key)))

                        response))))))

(defmacro wrap-with-metrics
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
  `(let [prefix# ~(str *ns*)]
    (wrap-with-metrics* ~app prefix# ~storage ~normalize-uri)))

(defn payload-to-body-string
  "Middleware to move the payload from the body or the payload param into the
  request property `:body-string`.

  Usually the body is an InputStream, it is first converted to a string."
  [app]
  (fn [{:keys [body params headers] :as req}]
    (let [content-type (headers "content-type")
          mediatype (if (nil? content-type) nil
                        (str (media/base-type content-type)))]
      (case mediatype
        "application/x-www-form-urlencoded"
        (if-let [payload (params "payload")]
          (app (assoc req :body-string payload))
          (http/error-response (str "Missing required parameter 'payload'")))
        "application/json"
        (let [body-string (request/body-string req)]
          (if (nil? body-string)
            (http/error-response (str "Empty body for application/json submission"))
            (app (assoc req :body-string body-string))))
        "multipart/form-data"
        (app (let [mp-request (mp/multipart-params-request req)
                   mp-params (:params mp-request)]
               (-> mp-request
                   (assoc :body-string (utils/synthesize-body-str mp-params))
                   (assoc :params (dissoc mp-params "command" "version" "certname" "payload")))))

        (if-let [payload (params "payload")]
          (app (assoc req :body-string payload))
          (http/error-response (str "Missing required parameter 'payload'")))))))

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
            {:status 413
             :headers {}
             :body "Command rejected due to size exceeding max-command-size"})
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

(defn wrap-with-parent-check
  "Middleware that checks the parent exists before serving the rest of the
   application. This ensures we always return 404's on child paths when the
   parent data is empty."
  [app version parent id]
  (fn [{:keys [globals] :as req}]
    (let [{:keys [scf-read-db url-prefix]} globals]
      ;; There is a race condition here, in particular we open up 1 transaction
      ;; for the parent test, but the rest of the query results are done via the
      ;; streaming query. This can't be solved until we work out a way to
      ;; pass through an existing db handle through to the streamed query thread.
      (if (jdbc/with-transacted-connection scf-read-db
            (qe/object-exists? parent id))
        (app req)
        (http/json-response {:error (str "No information is known about " (name parent) " " id)} http/status-not-found)))))
