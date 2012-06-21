;; ## Ring middleware

(ns com.puppetlabs.middleware
  (:require [com.puppetlabs.utils :as utils]
            [ring.util.response :as rr])
  (:use [metrics.timers :only (timer time!)]
        [metrics.meters :only (meter mark!)]))

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
          (rr/status 403)))))

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

(defn wrap-with-globals
  "Ring middleware that will add to each request a :globals attribute:
  a map containing various global settings"
  [app globals]
  (fn [req]
    (let [new-req (assoc req :globals globals)]
      (app new-req))))

(defn wrap-with-metrics*
  "Ring middleware that will tack performance counters for each
  URL. Arguments are the same as for `wrap-with-metrics`, except:

  `prefix`: string to use as the first component of each generated
  metric."
  [app prefix storage normalize-uri]
  (fn [req]
    (let [metric-root (str (normalize-uri (:uri req)))
          timer-key   [:timers metric-root]]
      (when-not (get-in @storage timer-key)
        (swap! storage assoc-in timer-key (timer [prefix metric-root "service-time"])))

      (time! (get-in @storage timer-key)

             (let [response  (app req)
                   status    (:status response)
                   meter-key [:meters metric-root status]]
               (when-not (get-in @storage meter-key)
                 (swap! storage assoc-in meter-key (meter [prefix metric-root (str status)] "reqs/s")))
               (mark! (get-in @storage meter-key))
               response)))))

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

  `normalize-uri`: A function that takes a URI, and returns a
  transformed string. The result will be used to organize the metrics:
  URI's with the same normalized representation will share timers and
  meters. To have all URIs share the same metrics, use `identity`."
  [app storage normalize-uri]
  `(let [prefix# ~(str *ns*)]
     (wrap-with-metrics* ~app prefix# ~storage ~normalize-uri)))
