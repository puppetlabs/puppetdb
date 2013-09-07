;; ## Ring middleware

(ns com.puppetlabs.middleware
  (:require [com.puppetlabs.utils :as utils]
            [com.puppetlabs.http :as pl-http]
            [ring.util.response :as rr]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [com.puppetlabs.puppetdb.http.paging :as paging])
  (:use [metrics.timers :only (timer time!)]
        [metrics.meters :only (meter mark!)]
        [clojure.walk :only (keywordize-keys)]))

(defn wrap-with-debug-logging
  "Ring middleware that logs incoming HTTP request URIs (at DEBUG level) as
  requests come in.  To enable, add this line to your log4j.properties:

  `log4j.logger.com.puppetlabs.middleware=DEBUG`"
  [app]
  (fn [req]
    (log/debug (str "Processing HTTP request to URI: '" (:uri req) "'"))
    (app req)))

(defn wrap-with-authorization
  "Ring middleware that will only pass through a request if the
  supplied authorization function allows it. Otherwise an HTTP 403 is
  returned to the client.

  `authorized?` is expected to take a single argument, the current
  request. The request is allowed only if the return value of
  `authorized?` is truthy."
  [app authorized?]
  (fn [req]
    (if (authorized? req)
      (app req)
      (-> "You shall not pass!"
          (rr/response)
          (rr/status pl-http/status-forbidden)))))

(defn wrap-with-certificate-cn
  "Ring middleware that will annotate the request with an
  :ssl-client-cn key representing the CN contained in the client
  certificate of the request. If no client certificate is present,
  the key's value is set to nil."
  [app]
  (fn [{:keys [ssl-client-cert] :as req}]
    (let [cn  (if ssl-client-cert
                (utils/cn-for-cert ssl-client-cert))
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
        (assoc response :body (pl-http/default-body req response))))))

(defn wrap-with-globals
  "Ring middleware that will add to each request a :globals attribute:
  a map containing various global settings"
  [app globals]
  (fn [req]
    (let [new-req (assoc req :globals globals)]
      (app new-req))))

(defn wrap-with-paging-options
  "Ring middleware that will add to each request a :paging-options attribute:
  a map optionally containing :limit, :offset, and :order-by keys used to
  implement result paging for the query.  The value for :order-by will be
  a list of maps, containing information about the fields to order the result
  by.  Each order-by map contains a key :field, and an optional key :order
  (whose value may be either 'asc' or 'desc', and defaults to 'asc')."
  [app]
  (fn [{:keys [params] :as req}]
    (app (assoc req :paging-options
           (-> params
             (select-keys ["limit" "offset" "order-by"])
             (keywordize-keys)
             (update-in [:limit] #(if (string? %) (utils/parse-int %) %))
             (update-in [:offset] #(if (string? %) (utils/parse-int %) %))
             (paging/parse-order-by))))))

(defn verify-accepts-content-type
  "Ring middleware that requires a request for the wrapped `app` to accept the
  provided `content-type`. If the content type isn't acceptable, a 406 Not
  Acceptable status is returned, with a message informing the client it must
  accept the content type."
  [app content-type]
  {:pre (string? content-type)}
  (fn [{:keys [headers] :as req}]
    (if (pl-http/acceptable-content-type
          content-type
          (headers "accept"))
      (app req)
      (rr/status (rr/response (str "must accept " content-type))
                 pl-http/status-not-acceptable))))

(defn verify-param-exists
  "Ring middleware that checks the existence of the given `param` in the
  request. If the param isn't specified, a 400 Not Found error is returned,
  explaining that the param is missing."
  [app param]
  {:pre (string? param)}
  (fn [{:keys [params] :as req}]
    (if (params param)
      (app req)
      (pl-http/error-response (str "missing " param)))))

(defn verify-no-paging-params
  "Ring middleware that verifies that none of the query parameters relating
  to paging ('limit', 'offset', 'order-by') are present.  This is used to
  provide a fail-fast indicator to users that the paging options are not
  supported on versions of the query API prior to v3."
  [app]
  (fn [{:keys [params] :as req}]
    (if-let [param (some #(if (contains? params %) %) ["limit" "offset" "order-by"])]
      (pl-http/error-response (str "Unsupported query parameter '" param "'"))
      (app req))))

(def verify-accepts-json
  "Ring middleware which requires a request for `app` to accept
  application/json as a content type. If the request doesn't accept
  application/json, a 406 Not Acceptable status is returned with an error
  message indicating the problem."
  (fn [app]
   (verify-accepts-content-type app "application/json")))

(defn verify-checksum
  "Ring middleware which will verify that the content of the `payload` param
  has the checksum specified in the `checksum` parameter. If no checksum is
  provided, this check will be skipped. If the checksum doesn't match, a 400
  Bad Request error is returned."
  [app]
  (fn [{:keys [params] :as req}]
    (let [expected-checksum (params "checksum")
          payload           (params "payload")]
      (if (and expected-checksum
               (not= expected-checksum (utils/utf8-string->sha1 payload)))
        (pl-http/error-response "checksums don't match")
        (app req)))))

(defn wrap-with-metrics*
  "Ring middleware that will tack performance counters for each
  URL. Arguments are the same as for `wrap-with-metrics`, except:

  `prefix`: string to use as the first component of each generated
  metric."
  [app prefix storage normalize-uri]
  (fn [req]
    (let [metric-roots (let [s (normalize-uri (:uri req))]
                         (if (string? s) [s] s))
          metric-roots (map #(s/replace % #"[:,=]" "_") metric-roots)]

      ;; Create timer objects for each metric the user has requested
      (doseq [metric-root metric-roots
              :let [timer-key [:timers metric-root]]
              :when (not (get-in @storage timer-key))]
        (swap! storage assoc-in timer-key (timer [prefix metric-root "service-time"])))

      (let [timers (map #(get-in @storage [:timers %]) metric-roots)]
        (utils/multitime! timers
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
