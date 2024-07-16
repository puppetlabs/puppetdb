(ns puppetlabs.puppetdb.query.monitor-test
  (:require
   [clj-http.client :as http]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [murphy :refer [try!]]
   [puppetlabs.puppetdb.query.monitor :as qmon]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.query-eng :refer [*diagnostic-inter-row-sleep*]]
   [puppetlabs.puppetdb.random :refer [random-string]]
   [puppetlabs.puppetdb.scf.storage :refer [add-certnames]]
   [puppetlabs.puppetdb.testutils :refer [default-timeout-ms]]
   [puppetlabs.puppetdb.testutils.db :refer [with-test-db *db*]]
   [puppetlabs.puppetdb.testutils.log :as tlog]
   [puppetlabs.puppetdb.testutils.services
    :refer [*base-url* with-puppetdb]]
   [puppetlabs.puppetdb.time :refer [ephemeral-now-ns]]
   [puppetlabs.puppetdb.utils :refer [base-url->str-with-prefix noisy-future]]
   [puppetlabs.trapperkeeper.logging :as pl-log :refer [root-logger-name]]
   [puppetlabs.trapperkeeper.testutils.logging
    :refer [with-log-level with-log-suppressed-unless-notable]])
  (:import
   (clojure.lang ExceptionInfo)
   (java.net InetAddress InetSocketAddress)
   (java.nio.channels ServerSocketChannel SocketChannel)
   (java.util.concurrent CountDownLatch TimeUnit)))

(def ns-per-ms 1000000)
(def ns-per-s  1000000000)

(defn channel []
  ;; Do this, rather than the obvious Pipe/open because Pipes didn't
  ;; appear to have any effect on Selector/select on close.
  (let [local (InetAddress/getLocalHost)
        srv (doto (ServerSocketChannel/open)
              (.bind (InetSocketAddress. local 0)))
        port (-> srv .getLocalAddress .getPort)
        server (future (.accept srv))
        client (doto (SocketChannel/open)
                 (.connect (InetSocketAddress. local port)))]
    [client (doto @server (.configureBlocking false))]))

(defn recording-terminator [atom latch]
  (fn record-termination [info context]
    ;; Run the function before capturing the args because mutable info
    ;; state will change.
    (let [pid (some-> info :pg-pid deref)] ;; capture any pid before termination
      (try!
        (#'qmon/terminate-query info context)
        (finally
          (swap! atom conj [(ephemeral-now-ns)
                            (-> info
                                (assoc :pg-pid pid)
                                (update :terminated #(some-> % (deref 1 ::timeout))))
                            context]))
        (finally
          (.countDown latch))))))

(defn summarize-termination [[termination-ns info context & more]]
  (assert (not more))
  {:termination-ns termination-ns
   :info (select-keys info [:query-id :deadline-ns :pg-pid :terminated])
   :context context})

(def termination-msg "terminating connection due to administrator command")

(defn sleepy-query
  "Invokes pg_sleep for the requested number of seconds in db.  On exit
  delivers to the end-time atom either [(ephemeral-now-ns) exception] or
  [(ephemeral-now-ns) nil]."
  [_who monitor skey db seconds result]
  (noisy-future
   (let [pid (atom nil)
         ex (try!
              (jdbc/with-db-connection db
                (reset! pid (jdbc/current-pid))
                (qmon/register-pg-pid monitor skey @pid)
                (try!
                  (jdbc/query-to-vec (format "select pg_sleep(%f);" (double seconds)))
                  nil
                  (finally
                    (qmon/forget-pg-pid monitor skey))))
              (catch Throwable ex
                ex))]
     (deliver result [(ephemeral-now-ns) @pid ex]))))

(deftest monitoring-basics
  ;; Monitor two queries that sleep, one that should finish before the
  ;; deadline, and one that shouldn't, and check the results.

  (with-test-db
    (let [terminations (atom [])
          notable? #(let [msg (.getMessage %)]
                      (not (str/includes? msg termination-msg)))
          expected-terminations 3
          termination-latch (CountDownLatch. expected-terminations)]
      (with-log-suppressed-unless-notable notable?
        (with-open [m (qmon/monitor :terminate-query (recording-terminator terminations
                                                                           termination-latch))]
          (qmon/start m)

          ;; Test a query that finishes before its deadline (ok), a
          ;; query that doesn't (exp), a query whose client disconnects
          ;; before it finishes or reaches its deadline (dis), and a
          ;; query that asks the monitor to forget it before its
          ;; deadline (bye).
          (let [[_ q-ok-chan] (channel)
                [_ q-exp-chan] (channel)
                [_ q-bye-chan] (channel)
                [q-dis-client q-dis-chan] (channel)

                q-ok-end (promise)
                q-exp-end (promise)
                q-bye-end (promise)
                q-dis-end (promise)

                start (ephemeral-now-ns)
                exp-deadline (+ start (* 0.5 ns-per-s))

                register (fn register-with-monitor [who ch when]
                           (qmon/stop-query-at-deadline-or-disconnect m who ch when *db*))

                q-ok-reg (register "q-ok" q-ok-chan exp-deadline)
                q-bye-reg (register "q-bye" q-bye-chan exp-deadline)
                q-exp-reg (register "q-exp" q-exp-chan exp-deadline)
                q-dis-reg (register "q-dis" q-dis-chan ##Inf)]

            (sleepy-query "q-ok" m q-ok-reg *db* 0.1 q-ok-end)
            (sleepy-query "q-bye" m q-bye-reg *db* 0.1 q-bye-end)
            (sleepy-query "q-exp" m q-exp-reg *db* 1.0 q-exp-end)
            (sleepy-query "q-dis" m q-dis-reg *db* 1.1 q-dis-end)

            (noisy-future (.close q-dis-client))

            (let [test-deadline (+ (ephemeral-now-ns) (* default-timeout-ms ns-per-ms))

                  remaining (- test-deadline (ephemeral-now-ns))
                  q-ok-result (deref q-ok-end (/ remaining ns-per-ms) [:nope :nope :nope])

                  remaining (- test-deadline (ephemeral-now-ns))
                  q-bye-result (deref q-bye-end (/ remaining ns-per-ms) [:nope :nope :nope])
                  _ (qmon/forget m q-bye-reg)

                  remaining (- test-deadline (ephemeral-now-ns))
                  q-exp-result (deref q-exp-end (/ remaining ns-per-ms) [:nope :nope :nope])

                  _ (qmon/forget m q-exp-reg)
                  remaining (- test-deadline (ephemeral-now-ns))
                  q-dis-result (deref q-dis-end (/ remaining ns-per-ms) [:nope :nope :nope])]
              (qmon/forget m q-dis-reg)

              (qmon/forget m q-ok-reg) ;; should be after the deadline given derefs

              (is (.await termination-latch 3 TimeUnit/SECONDS)) ;; just more than ~1

              (let [[_end-ns res-ok-pid res-ok-ex] q-ok-result
                    [_end-ns res-bye-pid res-bye-ex] q-bye-result
                    [_end-ns res-exp-pid res-exp-ex] q-exp-result
                    [_end-ns res-dis-pid res-dis-ex] q-dis-result]

                (is (int? res-ok-pid))
                (is (not res-ok-ex))

                (is (int? res-bye-pid))
                (is (not res-bye-ex))

                (is (int? res-exp-pid))
                (is (instance? ExceptionInfo res-exp-ex))
                ;; Newer versions of clojure.jdbc wrap the sql exception with ex-info.
                (is (str/includes? (-> res-exp-ex ex-data :handling .getMessage)
                                   termination-msg))

                (is (int? res-dis-pid))
                (is (not res-dis-ex))

                (let [terminations @terminations
                      summary (mapv summarize-termination terminations)]

                  ;; Check the count since we're comparing a set
                  (is (= expected-terminations (count terminations)))

                  ;; q-ok shows up (as comapred to q-bye) because we
                  ;; didn't promptly forget it.
                  (is (= #{{:info {:query-id "q-ok" ;; wasn't forgotten (like bye)
                                   :pg-pid nil ;; gone before monitor acts
                                   :deadline-ns exp-deadline
                                   :terminated true}
                            :context "expired"}
                           {:info {:query-id "q-dis"
                                   :pg-pid nil ;; gone before monitor acts
                                   :deadline-ns ##Inf
                                   :terminated nil}
                            :context "abandoned"}
                           {:info {:query-id "q-exp"
                                   :pg-pid res-exp-pid
                                   :deadline-ns exp-deadline
                                   :terminated true}
                            :context "expired"}}
                         (->> summary (mapv #(dissoc % :termination-ns)) set)))
                  (let [term-ns (-> (filter #(= "q-exp" (get-in % [:info :query-id]))
                                            summary)
                                    first
                                    :termination-ns)]
                    (is (> term-ns start))
                    (is (> term-ns exp-deadline))
                    (let [tolerance-ns (* 0.3 (- exp-deadline start))
                          upper-limit (+ exp-deadline tolerance-ns)]
                      (when-not (is (< term-ns upper-limit))
                        (binding [*out* *err*]
                          (println "query termination missed deadline by"
                                   (-> (- upper-limit term-ns) (/ ns-per-s) double)
                                   "seconds"))))))))))))))

(def certnames (repeatedly 100 #(random-string 2000)))

(defn impatient-nodes-request [timeout-ms]
  (let [res (-> (assoc *base-url* :prefix "/pdb/query/v4/nodes")
                base-url->str-with-prefix
                (http/get {:async? true
                           :throw-exceptions false
                           :content-type :json
                           :character-encoding "UTF-8"
                           :decompress-body false
                           :accept :json
                           :as :stream}
                          (fn [_res] true)
                          (fn [_ex] true)))]
    (Thread/sleep timeout-ms)
    (.cancel res true)))

(deftest external-query-disconnection
  ;; Test connections coming in from the "outside" (i.e. via http port
  ;; to real server instance).
  (let [terminations (atom [])
        real-terminate-query @#'qmon/terminate-query
        record-term (fn record-termination [info context]
                      (let [pid (some-> info :pg-pid deref)]
                        (try!
                          (real-terminate-query info context)
                          (finally
                            (swap! terminations conj
                                   [(ephemeral-now-ns)
                                    (-> info
                                        (assoc :pg-pid pid)
                                        (update :terminated #(some-> % (deref 1 ::timeout))))
                                    context])))))
        logged-terminations (atom [])
        saw-termination (promise)
        handle-event #(let [msg (.getMessage %)]
                        (cond
                          (str/includes? msg "Terminated abandoned")
                          (do
                            (deliver saw-termination true)
                            (swap! logged-terminations conj %)
                            false)

                          (and (str/includes? msg "FATAL: terminating connection due to administrator command")
                               (str/includes? msg ":cause Connection is closed"))
                          false

                          :else (tlog/notable-pdb-event? %)))]

    (with-redefs [qmon/terminate-query record-term]
      (with-puppetdb nil
        (jdbc/with-db-transaction [] (add-certnames certnames))
        (with-redefs [*diagnostic-inter-row-sleep* 1]
          (with-log-level root-logger-name :info
            (with-log-suppressed-unless-notable handle-event
              (impatient-nodes-request 200)
              (is (not= :timeout (deref saw-termination default-timeout-ms :timeout))))))))

    (let [terminations @terminations
          summary (mapv summarize-termination terminations)]
      (is (= 1 (count @logged-terminations)))
      (is (= 1 (count terminations)))
      (is (= #{{:info {:pg-pid true
                       :deadline-ns ##Inf
                       :terminated nil}
                :context "abandoned"}}
             (set (mapv #(-> (dissoc % :termination-ns)
                             (update :info dissoc :query-id)
                             (update-in [:info :pg-pid] int?))
                        summary)))))))

(deftest connection-reuse
  (testing "connection reuse with simple queries"
    ;; Test multiple queries over the same connection.  Run the test
    ;; multiple times because the first problem we encountered could
    ;; only occur during a narrow window in the monitor loop if a new
    ;; request came in between select invocations, after the key had
    ;; been cancelled.
    ;; https://github.com/puppetlabs/puppetdb/issues/3866
    (with-puppetdb nil
      (jdbc/with-db-transaction [] (add-certnames certnames))
      ;; Just use curl, since it's trivally easy to get it to do what we
      ;; need, and we already require it for test setup (via ext/).  (It
      ;; looks like both clj-http and the JDK HttpClient have more
      ;; indirect control over reuse.)
      (let [nodes (-> (assoc *base-url* :prefix "/pdb/query/v4/nodes")
                      base-url->str-with-prefix)
            cmd ["curl" "--no-progress-meter" "--show-error" "--fail-early"
                 "--fail" nodes "-o" "/dev/null"
                 "--next" "--fail" nodes "-o" "/dev/null"
                 "--next" "--fail" nodes "-o" "/dev/null"]]
        (loop [i 0]
          (let [{:keys [exit out err]} (apply sh cmd)]
            (when (< i 10)
              (if (is (= 0 exit))
                (recur (inc i))
                (do
                  (apply println "Failed:" cmd)
                  (print out)
                  (print err)))))))))

  (testing "connection reuse with non-streaming queries"
    (with-puppetdb nil
      (jdbc/with-db-transaction [] (add-certnames certnames))
      (let [q (-> (assoc *base-url* :prefix "/pdb/query/v4")
                  base-url->str-with-prefix)
            query (fn [q] (str "{\"query\":\"" q "\"}"))
            ast-q (fn [q] (str "{\"query\":\"" q "\", \"ast_only\":true}"))
            cmd ["curl" "-X" "POST" "--no-progress-meter" "--show-error" "--fail-early"
                 "--fail" "--max-time" "1" q "-H" "Content-Type:application/json"
                 "-d" (ast-q "nodes{}") "-o" "/dev/null"
                 "--next"
                 "--fail" "--max-time" "1" q "-H" "Content-Type:application/json"
                 "-d" (query "nodes{}") "-o" "/dev/null"]
            {:keys [exit out err]} (apply sh cmd)]
        (if (is (= 0 exit))
          nil
          (do
            (apply println "Failed:" cmd)
            (print out)
            (print err)))))))
