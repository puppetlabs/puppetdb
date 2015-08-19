(ns puppetlabs.puppetdb.http.command
  (:require [puppetlabs.puppetdb.command.constants :refer [command-names]]
            [puppetlabs.trapperkeeper.core :refer [defservice]]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [puppetlabs.puppetdb.command :as command]
            [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.cheshire :as json]
            [net.cgrand.moustache :as moustache]
            [puppetlabs.puppetdb.middleware :as mid]
            [compojure.core :as compojure]
            [clojure.core.async :as async]
            [puppetlabs.kitchensink.core :as kitchensink]))

(def min-supported-commands
  {"replace catalog" 6
   "replace facts" 4
   "store report" 5
   "deactivate node" 3})

(def valid-commands-str (str/join ", " (sort (vals command-names))))

(defn validate-command-version
  [app]
  (fn [{:keys [body-string] :as req}]
    (let [{:keys [command version]} (json/parse-string body-string true)
          min-supported (get min-supported-commands command ::invalid)]
      (cond
        (= ::invalid min-supported)
        (http/bad-request-response
          (format "Supported commands are %s. Received '%s'."
                  valid-commands-str command))

        (< version min-supported)
        (http/bad-request-response
          (format "%s version %s is retired. The minimum supported version is %s."
                  command version min-supported))

        :else (app req)))))

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
                                           503)
          response-chan ([{:keys [command exception]}]
                         (let [base-response {:uuid uuid
                                              :processed true}]
                           (if exception
                             (http/json-response (assoc base-response
                                                        :timed_out false
                                                        :error (str exception)
                                                        :stack_trace (map str (.getStackTrace exception)))
                                                 503)
                             (http/json-response (assoc base-response
                                                        :timed_out false)
                                                 200)))))))))

(defn enqueue-command-handler
  "Enqueues the command in request and returns a UUID"
  [get-command-mq enqueue-fn get-response-pub]
  (fn [{:keys [body-string params] :as request}]
    (let [uuid (kitchensink/uuid)
          completion-timeout-ms (some-> params
                                        (get "secondsToWaitForCompletion")
                                        Double/parseDouble
                                        (* 1000))
          {:keys [connection endpoint]} (get-command-mq)
          do-submit #(enqueue-fn connection endpoint body-string uuid)]
      (if (some-> completion-timeout-ms pos?)
        (blocking-submit-command do-submit (get-response-pub) uuid completion-timeout-ms)
        (do
          (do-submit)
          (http/json-response {:uuid uuid}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

;; The below fns expect to be called from a moustache handler and
;; return functions that accept a ring request map

(defn command-app
  [get-authorizer get-command-mq get-shared-globals enqueue-fn get-response-pub]
  (-> (moustache/app
       ["v1" &] {:any (enqueue-command-handler get-command-mq enqueue-fn get-response-pub)})
      validate-command-version
      mid/verify-accepts-json
      mid/verify-checksum
      (mid/validate-query-params {:optional ["checksum" "secondsToWaitForCompletion"]})
      mid/payload-to-body-string
      (mid/verify-content-type ["application/json"])
      (mid/wrap-with-puppetdb-middleware get-authorizer)
      (mid/wrap-with-metrics (atom {}) http/leading-uris)
      (mid/wrap-with-globals get-shared-globals)))

(defprotocol PuppetDBCommand
  (submit-command
    [this command version payload]
    [this command version payload uuid]))

(defservice puppetdb-command-service
  PuppetDBCommand
  [[:PuppetDBServer shared-globals]
   [:PuppetDBCommandDispatcher enqueue-command]]

  (start [this context]
         (log/info "Starting command service")
         context)

  (submit-command [this command version payload]
    (submit-command this command version payload nil))

  (submit-command [this command version payload uuid]
    (let [{{:keys [connection endpoint]} :command-mq} (shared-globals)]
      (enqueue-command connection endpoint
                       (command-names command) version payload uuid))))
