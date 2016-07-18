(ns puppetlabs.puppetdb.mq-listener
  (:import [javax.jms ExceptionListener JMSException MessageListener Session
            ConnectionFactory Connection Queue Message]
           [java.util.concurrent Semaphore ThreadPoolExecutor TimeUnit SynchronousQueue
            RejectedExecutionException ExecutorService]
           [org.apache.commons.lang3.concurrent BasicThreadFactory BasicThreadFactory$Builder])
  (:require [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n]
            [puppetlabs.puppetdb.command.dlo :as dlo]
            [puppetlabs.puppetdb.mq :as mq]
            [puppetlabs.kitchensink.core :as kitchensink]
            [puppetlabs.puppetdb.cheshire :as json]
            [slingshot.slingshot :refer [try+ throw+]]
            [puppetlabs.puppetdb.metrics.core :as metrics]
            [metrics.meters :refer [meter mark!]]
            [metrics.histograms :refer [histogram update!]]
            [metrics.timers :refer [timer time!]]
            [puppetlabs.puppetdb.command :as cmd]
            [puppetlabs.trapperkeeper.services :refer [defservice service-context service-id]]
            [schema.core :as s]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.schema :as pls]
            [clojure.core.async :as async]
            [puppetlabs.puppetdb.utils.metrics :as mutils]
            [puppetlabs.puppetdb.threadpool :as gtp]))

;; ## Performance counters

;; For each command (and globally), add command-processing
;; metrics. The hierarchy of command-processing metrics has the
;; following form:
;;
;;     {"global"
;;        {:seen <meter>
;;         :processed <meter>
;;         :fatal <meter>
;;         :retried <meter>
;;         :discarded <meter>
;;         :processing-time <timer>
;;         :retry-counts <histogram>
;;        }
;;      "command name"
;;        {<version number>
;;           {:seen <meter>
;;            :processed <meter>
;;            :fatal <meter>
;;            :retried <meter>
;;            :discarded <meter>
;;            :processing-time <timer>
;;            :retry-counts <histogram>
;;           }
;;        }
;;     }
;;
;; The `"global"` hierarchy contains metrics aggregated across all
;; commands.
;;
;; * `:seen`: the number of commands (valid or not) encountered
;;
;; * `:processeed`: the number of commands successfully processed
;;
;; * `:fatal`: the number of fatal errors
;;
;; * `:retried`: the number of commands re-queued for retry
;;
;; * `:discarded`: the number of commands discarded for exceeding the
;;   maximum allowable retry count
;;
;; * `:processing-time`: how long it takes to process a command
;;
;; * `:retry-counts`: histogram containing the number of times
;;   messages have been retried prior to suceeding
;;
(def mq-metrics-registry (get-in metrics/metrics-registries [:mq :registry]))

(defn create-metrics [prefix]
  (let [to-metric-name-fn #(metrics/keyword->metric-name prefix %)]
    {:processing-time (timer mq-metrics-registry (to-metric-name-fn :processing-time))
     :retry-persistence-time (timer mq-metrics-registry (to-metric-name-fn :retry-persistence-time))
     :generate-retry-message-time (timer mq-metrics-registry (to-metric-name-fn :generate-retry-message-time))
     :retry-counts (histogram mq-metrics-registry (to-metric-name-fn :retry-counts))
     :seen (meter mq-metrics-registry (to-metric-name-fn :seen))
     :processed (meter mq-metrics-registry (to-metric-name-fn :processed))
     :fatal (meter mq-metrics-registry (to-metric-name-fn :fatal))
     :retried (meter mq-metrics-registry (to-metric-name-fn :retried))
     :discarded (meter mq-metrics-registry (to-metric-name-fn :discarded))}))

(def metrics (atom {:global (create-metrics [:global])}))

(defn global-metric
  "Returns the metric identified by `name` in the `\"global\"` metric
  hierarchy"
  [name]
  {:pre [(keyword? name)]}
  (get-in @metrics [:global name]))

(defn cmd-metric
  [cmd version name]
  {:pre [(keyword? name)]}
  (get-in @metrics [(keyword (str cmd version)) name]))

(defn create-metrics-for-command!
  "Create a subtree of metrics for the given command and version (if
  present).  If a subtree of metrics already exists, this function is
  a no-op."
  [command version]
  (let [storage-path [(keyword (str command version))]]
    (when (= ::not-found (get-in @metrics storage-path ::not-found))
      (swap! metrics assoc-in storage-path
             (create-metrics [(keyword command) (keyword (str version))])))))

(defn fatal?
  "Tests if the supplied exception is a fatal command-processing
  exception or not."
  [exception]
  (:fatal exception))

;; ## MQ I/O
;;
;; The data flow through the code is as follows:
;;
;; * A message is read off of an MQ endpoint
;;
;; * The message is fed through a _message processor_: a function that
;;   takes a message as the only argument
;;
;; * Repeat ad-infinitum
;;

;; ## MQ processing middleware
;;
;; The parsing and processing of incoming commands is architected as a
;; set of _middleware_ functions. That is, higher-order functions that
;; add capabilities to an existing message-handling
;; function. Middleware can be stacked, creating sophisticated
;; hierarchies of functionality. And because each middleware function
;; is isolated in terms of capability, testability is much simpler.
;;
;; It's not an original idea; it was stolen from _Ring's_ middleware
;; architecture.
;;

(defn annotate-with-attempt
  "Adds an `attempt` annotation to `msg` indicating there was a failed attempt
  at handling the message, including the error and trace from `e`."
  [{:keys [annotations] :as msg} e]
  {:pre  [(map? annotations)]
   :post [(= (count (get-in % [:annotations :attempts]))
             (inc (count (:attempts annotations))))]}
  (let [attempts (get annotations :attempts [])
        attempt  {:timestamp (kitchensink/timestamp)
                  :error     (str e)
                  :trace     (map str (.getStackTrace e))}]
    (update-in msg [:annotations :attempts] conj attempt)))

;; The number of times a message can be retried before we discard it
(def maximum-allowable-retries 16)

(defn mark-both-metrics!
  "Calls `mark!` on the global and command specific metric for `k`"
  [command version k]
  (mark! (global-metric k))
  (mark! (cmd-metric command version k)))

(defn update-both-metrics!
  "Calls `update!` on the global and command specific metric for `k`"
  [command version k v]
  (update! (global-metric k) v)
  (update! (cmd-metric command version k) v))

(defn call-with-command-metrics
  "Invokes `f` including the related metrics updates"
  [command version retries f]
  (create-metrics-for-command! command version)

  (mark-both-metrics! command version :seen)
  (update-both-metrics! command version :retry-counts retries)

  (mutils/multitime!
   [(global-metric :processing-time)
    (cmd-metric command version :processing-time)]

   (let [command-result (f)]
     (mark-both-metrics! command version :processed)
     command-result)))

(defn parse-command
  [msg]
  (try+
   (cmd/parse-command msg)
   (catch AssertionError e
     (throw+ {:kind ::parse-error} e "Error parsing command"))
   (catch Exception e
     (throw+ {:kind ::parse-error} e "Error parsing command"))))

(defn message-handler-with-retries
  "This function processes the message, retrying messages that
  fail and discarding messages that have fatal errors or have exceeded
  their maximum allowed attempts. `delay-message-fn` and
  `discard-message-fn` are both functions of two arguments, a
  `message` and an `exception`. `process-message-fn` is a function
  that accepts a message as it's argument"
  [delay-message discard-message process-message]
  (fn [msg]
    (try+
     (let [{:keys [command version annotations] :as parsed-result} (parse-command msg)
           retries (count (:attempts annotations))
           id (:id annotations)
           certname (get-in parsed-result [:payload :certname])]

       (try+
        (call-with-command-metrics command version retries
                                   #(process-message parsed-result))

        (catch fatal? obj
          (mark! (global-metric :fatal))
          (let [ex (:cause obj)]
            (log/error (:wrapper &throw-context) (i18n/trs "[{0}] [{1}] Fatal error on attempt {2} for {3}" id command retries certname))
            (-> parsed-result
                (annotate-with-attempt ex)
                (discard-message ex))))

        (catch Exception exception
          (let [certname (get-in parsed-result [:payload :certname])
                ex (:throwable &throw-context)
                log-str (i18n/trs "[{0}] [{1}] Retrying after attempt {2} for {3}, due to: {4}"
                                  id command retries certname ex)]
            (mark-both-metrics! command version :retried)
            (cond
              (< retries 4)
              (do
                (log/debug exception log-str)
                (delay-message parsed-result exception))

              (< retries maximum-allowable-retries)
              (do
                (log/errorf exception log-str)
                (delay-message parsed-result exception))

              :else
              (do
                (log/error ex (i18n/trs "[{0}] [{1}] Exceeded max {2} attempts for {3}" id command retries certname))
                (discard-message parsed-result nil)))))))

     (catch [:kind ::parse-error] _
       (mark! (global-metric :fatal))
       (log/error (:wrapper &throw-context) (i18n/trs "Fatal error parsing command: {0}" msg))
       (discard-message msg (:throwable &throw-context))))))

(defprotocol MessageListenerService
  (register-listener [this schema listener-fn])
  (process-message [this message]))

(def message-fn-schema
  (s/make-fn-schema s/Any {s/Any s/Any}))

(def handler-schema
  [[(s/one message-fn-schema "Predicate Function")
    (s/one message-fn-schema "Message Handler Function")]])

(pls/defn-validated matching-handler
  "Takes a list of pred/handler pairs and returns the first matching handler
   for the given message"
  [handlers :- handler-schema
   message :- {s/Any s/Any}]
  (first
   (for [[pred handler] handlers
         :when (pred message)]
     handler)))

(pls/defn-validated conj-handler
  "Conjs the predicate and message handler onto the `listener-atom` list"
  [listener-atom :- clojure.lang.Atom
   pred :- message-fn-schema
   handler-fn :- message-fn-schema]
  (swap! listener-atom conj [pred handler-fn]))

(defn ^Session create-session [^Connection connection]
  (.createSession connection false Session/CLIENT_ACKNOWLEDGE))

(defn ^Queue create-queue [^Session session endpoint]
  (.createQueue session endpoint))

(defn ^Connection create-connection [^ConnectionFactory factory]
  (doto (.createConnection factory)
    (.setExceptionListener
     (reify ExceptionListener
       (onException [this ex]
         (log/error ex "receiver queue connection error"))))
    (.start)))

(defn send-delayed-message [{:keys [^ConnectionFactory conn-pool endpoint]}]
  (fn [message exception]
    (time!
     (global-metric :retry-persistence-time)
     (with-open [connection (create-connection conn-pool)
                 session (create-session connection)]
       (let [q (create-queue session endpoint)
             message-with-error (-> message
                                    (annotate-with-attempt exception)
                                    json/generate-string)]
         (with-open [producer (.createProducer session q)]
           (->> (mq/delay-property 1 :seconds)
                (mq/to-jms-message session message-with-error)
                (.send producer))))))))

(defn discard-message [discard-dir]
  (fn [message exception]
    (dlo/store-failed-message message exception discard-dir)))

(defn create-command-consumer
  "Create and return a command handler. This function does the work of
  consuming/storing a command. Handled commands are acknowledged here"
  [{:keys [discard-dir] :as mq-context} command-handler]
  (let [handle-message (message-handler-with-retries (send-delayed-message mq-context)
                                                     (discard-message discard-dir)
                                                     command-handler)]
    (fn [^Message message]
      ;; When the queue is shutting down, it sends nil message
      (when message
        (try
          (handle-message (mq/convert-jms-message message))
          (.acknowledge message)
          (catch Exception ex
            (log/error ex "Unable to process message. Message not acknowledged and will be retried")))))))

(defn create-mq-receiver
  "Infinite loop, intended to be run in an isolated thread that
  recieves a message from ActiveMQ, passes it to `mq-message-handler`
  and looks for the next message"
  [{:keys [^ConnectionFactory conn-pool endpoint]} command-chan mq-connected-promise]
  (with-open [connection (create-connection conn-pool)
              session (create-session connection)
              consumer (.createConsumer session (create-queue session endpoint))]
    (try
      ;; delivering the promise here we have ensured that we can
      ;; connect to the MQ and that the queue has been created (for
      ;; the first startup of PuppetDB)
      (deliver mq-connected-promise true)
      (loop []
        (if-let [msg (.receive consumer)]
          (do
            (async/>!! command-chan msg)
            (recur))
          (async/close! command-chan)))
      (catch javax.jms.IllegalStateException e
        (log/info "Received IllegalStateException, shutting down"))
      (catch Exception e
        (log/error e)))))

(defn create-command-handler-threadpool
  "Creates an unbounded threadpool with the intent that access to the
  threadpool is bounded by the semaphore. Implicitly the threadpool is
  bounded by `size`, but since the semaphore is handling that aspect,
  it's more efficient to use an unbounded pool and not duplicate the
  constraint in both the semaphore and the threadpool"
  [size]
  (gtp/create-threadpool size "cmd-proc-thread-%d" 10000))

(defn run-mq-listener-thread
  "Creates (and starts) an isolated thread to continually receive new
  ActiveMQ messages and pass them to the `message-consumer-fn`"
  [mq-context message-consumer-fn mq-connected-promise]
  (doto (Thread. (fn []
                   (create-mq-receiver mq-context message-consumer-fn mq-connected-promise)))
    (.setDaemon false)
    (.start)))

(defn create-mq-context
  "The MQ context contains a connection pool that pools both ActiveMQ
  connections and sessions"
  [config]
  {:discard-dir (conf/mq-discard-dir config)
   :endpoint (conf/mq-endpoint config)
   :conn-pool (mq/activemq-connection-factory (conf/mq-broker-url config))})

(defservice message-listener-service
  MessageListenerService
  [[:DefaultedConfig get-config]
   [:PuppetDBServer]] ; MessageListenerService depends on the broker

  (init [this context]
        (assoc context :listeners (atom [])))

  (start [this context]
    (let [config (get-config)
          mq-context (create-mq-context config)
          command-threadpool (create-command-handler-threadpool (conf/mq-thread-count config))
          command-handler #(process-message this %)
          command-chan (async/chan (conf/mq-thread-count config))
          message-handler (create-command-consumer mq-context command-handler)
          mq-connected-promise (promise)]

      (run-mq-listener-thread mq-context command-chan mq-connected-promise)

      (doto (Thread. (fn []
                       (gtp/dochan command-threadpool message-handler command-chan)))
        (.setDaemon false)
        (.start))

      @mq-connected-promise

      (assoc context
             :conn-pool (:conn-pool mq-context)
             :command-chan command-chan
             :consumer-threadpool command-threadpool)))

  (stop [this {:keys [conn-pool consumer-threadpool command-chan] :as context}]
        (async/close! command-chan)
        (gtp/shutdown consumer-threadpool)
        (.stop conn-pool)
    context)

  (register-listener [this pred listener-fn]
    (conj-handler (:listeners (service-context this)) pred listener-fn))

  (process-message [this message]
    (if-let [handler-fn (matching-handler @(:listeners (service-context this))
                                          message)]
      (handler-fn message)
      (log/warnf "No message handler found for %s" message))))
