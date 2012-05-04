;; ## Ring middleware

(ns com.puppetlabs.middleware
  (:use [metrics.timers :only (timer time!)]
        [metrics.meters :only (meter mark!)]))

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
