(ns puppetlabs.puppetdb.query.monitor

  "This provides a monitor for in-progress queries.  The monitor keeps
  track of each registered query's deadline, client socket (channel),
  and possible postgresql connection, and whenever the deadline is
  reaached or the client disconnects (the query is abandoned), the
  monitor will attempt to kill the query -- currently by invoking a
  pg_terminate() on the query's registered postgres pid.

  The main focus is client disconnections since there didn't appear to
  be any easy way to detect/handle them otherwise, and because without
  the pg_terminate, the server might continue executing an expensive
  query for a long time after the client is gone (say via browser page
  refresh).

  It's worth noting that including this monitor, we have three
  different query timeout mechanisms.  The other two are the
  time-limited-seq and the jdbc/update-local-timeouts operations in
  query-eng.  We have all three because the time-limited seq only
  works when rows are moving (i.e. not when blocked waiting on pg or
  blocked pushing json to the client), the pg timeouts have an unusual
  granularity, i.e. they're a per-pg-wire-batch timeout, not a timeout
  for an entire statement like a top-level select, and the
  pg_terminate()s used here in the monitor are more expensive than
  either of those (killing an entire pg worker process).

  The core of the monitor is a traditional (here NIO based) socket
  select loop, which should be able to handle even a large number of
  queries reasonably efficiently, and without requiring some number of
  threads proportional to the in-progress query count.

  The current implementation is intended to respect the Selector
  concurrency requirements, aided in part by limiting most work to the
  single monitor thread, though `forget` does compete with the monitor
  loop (coordinating via the `:terminated` promise.

  No operations should block forever; they should all eventually (in
  some cases customizably) time out, and the current implementation is
  intended, overall, to try to let pdb keep running, even if the
  monitor (thread) dies somehow.  The precipitating errors should
  still be reported to the log.

  Every monitored query will have a SelectionKey associated with it,
  The key is cancelled during forget, but won't be removed from the
  selector's cancelled set until the next call to select.  During that
  time, another query on the same socket/connection could try to
  re-register the cancelled key.  This will throw an exception, which
  we suppress and retry until the select loop finally removes the
  cancelled key, and we can re-register the socket.

  Every monitored query may also have a postgres pid associated with
  it, and whenever it does, that pid should be terminated (in
  coordination with the :terminated promise) once the query has been
  abandoned or has timed out.

  The terminated promise coordinates between the monitor and attempts
  to remove (forget) a query.  The arrangement is intended to make
  sure that the attempt to forget doesn't return until any competing
  termination attempt has finished, or at least had a chance to
  finish (otherwise the termination could kill a pg worker that's no
  longer associated with the original query, i.e. it's handling a new
  query that jetty has picked up on that channel).

  The client socket monitoring depends on access to the jetty query
  response which (at least at the moment) provides indirect access to
  the java socket channel which can be read to determine whether the
  client is still connected.

  The current implementation is completely incompatible with http
  \"pipelining\", but it looks like that is no longer a realistic
  concern:
  https://daniel.haxx.se/blog/2019/04/06/curl-says-bye-bye-to-pipelining/

  If that turns out to be an incorrect assumption, then we'll have to
  reevaluate the implementation and/or feasibility of the monitoring.
  That's because so far, the only way we've found to detect a client
  disconnection is to attempt to read a byte.  At the moment, that's
  acceptable because the client shouldn't be sending any data during
  the response (which of course wouldn't be true with pipelining,
  where it could be sending additional requests)."

  (:require
   [clojure.tools.logging :as log]
   [murphy :refer [try!]]
   [puppetlabs.i18n.core :refer [trs]]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.query.common :refer [bad-query-ex]]
   [puppetlabs.puppetdb.scf.storage-utils :refer [db-metadata]]
   [puppetlabs.puppetdb.time :refer [ephemeral-now-ns]]
   [puppetlabs.puppetdb.utils :refer [with-noisy-failure]]
   [schema.core :as s])
  (:import
   (java.lang AutoCloseable)
   (java.nio ByteBuffer)
   (java.nio.channels CancelledKeyException
                      ClosedChannelException
                      ReadableByteChannel
                      SelectableChannel
                      SelectionKey
                      Selector
                      SocketChannel)))

(def ^:private warn-on-reflection-orig *warn-on-reflection*)
(set! *warn-on-reflection* true)

(def ns-per-ms 1000000)

(defn state-summary [{:keys [deadlines selector-keys]}]
  {:selectors (keys selector-keys)
   :deadlines (->> deadlines vals
                   (map #(select-keys % [:query-id :deadline-ns :pg-pid :terminated]))
                   (map #(assoc % :remaining-sec
                                (-> (- (:deadline-ns %) (ephemeral-now-ns))
                                    (/ 1000000000)
                                    double))))})

(defn- terminate-query
  "Terminates any pg-pid connection provided by the info Always delivers
  true to any terminated promise."
  [{:keys [query-id db pg-pid terminated] :as _info} what]
  (if-not pg-pid
    (log/error "Expected internal pg-pid missing (please report)")
    (try
      (when-let [pg-pid @pg-pid]
        (let [[{:keys [pg_terminate_backend]} :as rows]
              (jdbc/with-db-connection db
                (jdbc/query-to-vec
                 (let [terminate (if (<= 14 (-> (db-metadata) :version first))
                                   "pg_terminate_backend(pid, 500)"
                                   "pg_terminate_backend(pid)")]
                   (str "select " terminate ", pid"
                        "  from pg_stat_activity"
                        "  where datname = (select current_database()) and pid = ?"))
                 pg-pid))]
          (cond
            (nil? pg_terminate_backend)
            (log/warn (trs "Unable to terminate {0} PDBQuery:{1} (no PostgreSQL pid {2})"
                           what query-id (str pg-pid)))
            pg_terminate_backend
            (log/info (trs "Terminated {0} PDBQuery:{1} (PostgreSQL pid {2})"
                           what query-id (str pg-pid)))
            :else
            (log/warn (trs "Unable to terminate {0} PDBQuery:{1} in 500ms (PostgreSQL pid {2})"
                           what query-id (str pg-pid))))
          (when (seq (rest rows))
            (log/error (trs "Attempt to terminate {0} PDBQuery:{0} found multiple candidates: {1}"
                            what query-id (pr-str rows))))))
      (finally
        (when terminated
          (deliver terminated true))))))

(defn- drop-query [queries [_deadline-ns selection-key :as deadlines-key]]
  (-> queries
      (update :deadlines dissoc deadlines-key)
      (update :selector-keys dissoc selection-key)))

(defn- next-expired-query!
  "Removes the next expired query (if any) from queries and returns the
  info and selection key for that query, and the next ns
  deadline (after that query) as [info deadline key].  Returns a false
  value if there are no expirations."
  [queries now]
  ;; Process just one query at a time so that each :terminate
  ;; wait/timeout will be independent.
  (let [{:keys [deadlines] :as cur} @queries
        [next-up] (seq deadlines)]
    ;; Grab the earliest deadline, if any.
    (when-let [[[deadline-ns dead-skey :as _dead-key] info]
               next-up]
      ;; Use compare-and-set! (not swap!) so we can return the next deadline.
      (if-not (<= deadline-ns now)
        [nil deadline-ns nil]
        ;; Swap in terminated, so that we know the pg-pid won't
        ;; change to some other query's, at least until we finish or
        ;; the query thread's deref times out.
        (let [info (assoc info :terminated (promise))
              new (update cur :selector-keys assoc dead-skey info)]
          (if (compare-and-set! queries cur new)
            [info nil dead-skey]
            (recur queries now)))))))

(defn- enforce-deadlines!
  "Attempts to terminate and remove everything expired from queries,
  and returns the next ns query deadline, or a false value if there
  isn't one."
  [queries now stop-query]
  (loop []
    (let [[info next-deadline select-key] (next-expired-query! queries now)]
      (if-not info
        next-deadline
        (do
          (try!
            (stop-query info "expired")
            (finally
              (swap! queries drop-query [(:deadline-ns info) select-key]))
            (finally
              (.cancel ^SelectionKey select-key)))
          (recur))))))

(defn- describe-key [^SelectionKey k]
  ;; Currently only works for keys that have channels with
  ;; getRemoteAddress...
  {:client (try
             (let [c ^SocketChannel (.channel k)]
               (-> c .getRemoteAddress str))
             (catch ClosedChannelException _ :closed))
   :ops (if-let [ops (try (.readyOps k) (catch CancelledKeyException _))]
          (set (for [[v n] [[SelectionKey/OP_ACCEPT :accept]
                            [SelectionKey/OP_CONNECT :connect]
                            [SelectionKey/OP_READ :read]
                            [SelectionKey/OP_WRITE :write]]
                     :when (pos? (bit-and v ops))]
                 n))
          :cancelled)})

(defn- disconnected?
  [^ReadableByteChannel chan ^ByteBuffer buf]
  ;; Various ssumptions here:
  ;;   - transport (chan) will be non-blocking
  ;;   - everyone, including jetty, etc. won't miss discarded bytes
  ;;   - transport will return -1 or exception when client is gone
  ;;   - typically there won't be any bytes, and read will return 0
  (.clear buf)
  (let [res (try! (.read chan buf) (catch ClosedChannelException _ :closed))]
    (case (long res) ;; This doesn't match -1 when res is an Integer...
      (-1 :closed) true
      (let [pos (.position buf)]
        ;; Client sent bytes.  Might indicate unexpected client
        ;; behavior, or a pdb bug (i.e. if we read from the socket
        ;; when we weren't supposed to, say after the current query
        ;; had finished and jetty is reusing the socket.
        ;; cf. (PE-37466)
        (log/info (trs "Read unexpected bytes ({0}) from client query connection" pos))
        (log/debug (apply str (trs "Unexpected client bytes: ")
                          (if (> pos 64)
                            [(String. (.array buf) 0 64 "UTF-8") "..."]
                            [(String. (.array buf) 0 pos "UTF-8")])))
        false))))

(defn- stop-abandoned!
  "Attempts to terminate every selected query whose client channel has
  closed, and then removes them from queries.  The buf (likely a
  ByteBuffer) is (re)used for a test client read.  Larger sizes will
  drain any unexpected client data faster.  In the longer run, for
  puppetdb, we could consider treating data from the client as an
  error."
  [queries selected stop-query buf]
  (let [dead? #(disconnected? (.channel ^SelectionKey %) buf)
        dead-keys (filterv dead? selected)]
    (doseq [^SelectionKey dead-key dead-keys]
      (.cancel dead-key)
      (let [info (-> @queries :selector-keys (get dead-key))]
        (log/warn (trs "Unexpected PDBQuery:{0} client disconnection: {1}"
                       (:query-id info)
                       (pr-str (describe-key dead-key))))
        (stop-query info "abandoned")))
    (swap! queries
           (fn [{:keys [selector-keys] :as cur}]
             (let [deadline-keys (mapv (fn [sk]
                                         [(-> selector-keys (get sk) :deadline-ns)
                                          sk])
                                       dead-keys)]
               (-> cur
                   (update :deadlines #(apply dissoc % deadline-keys))
                   (update :selector-keys #(apply dissoc % dead-keys))))))))

(defn- deadline->select-timeout
  "Returns selector timeout ms given an ephemeral deadline-ns."
  [deadline-ns]
  (cond
    (= ##Inf deadline-ns) 0 ;; block select forever
    (= ##-Inf deadline-ns) 1 ;; expired; unblock select as fast as we can
    :else (max 1 (int (/ (- deadline-ns (ephemeral-now-ns))
                         ns-per-ms)))))

(defn- monitor-queries [{:keys [exit queries ^Selector selector] :as _monitor}
                        terminate-query]
  ;; We depend on the fact that any new queries will wake us
  ;; up (i.e. wrt possible deadline advancements).
  (with-noisy-failure
    ;; Create a non-trivial buffer so we'll clear out any noise from
    ;; the client (shouldn't be any).  On an X release, suppose we
    ;; could make that an error.
    (let [buf (ByteBuffer/allocate 4096)]
      (try!
        (loop []
          (when-not @exit
            (if-let [next-deadline (enforce-deadlines! queries (ephemeral-now-ns)
                                                       terminate-query)]
              (.select selector ^long (deadline->select-timeout next-deadline))
              (.select selector))
            (stop-abandoned! queries (.selectedKeys selector) terminate-query buf)
            (recur)))
        (finally
          (log/info (trs "Query monitor shutting down")))
        (finally
          (doseq [[_ {:keys [terminated]}] (:selector-keys @queries)
                  :when terminated]
            (deliver terminated :shutdown)))
        (finally
          (.close selector))))))

(declare stop)

(s/defrecord PuppetDBQueryMonitor
    [exit selector queries terminate-query thread]
  AutoCloseable
  (close [this] (stop this)))

;;(defn monitor? [x] (instance? PuppetDBQueryMonitor x))

(defn compare-deadline-keys [[deadline-1 skey-1] [deadline-2 skey-2]]
  (let [deadlines (compare deadline-1 deadline-2)]
    (if-not (zero? deadlines)
      deadlines
      (compare (.hashCode skey-1) (.hashCode skey-2)))))

(defn monitor
  [& {:keys [terminate-query]
      :or {terminate-query puppetlabs.puppetdb.query.monitor/terminate-query}}]

  ;; With the current implementation, there may not always be an entry
  ;; in :deadlines corresponding to an entry in :selector-keys.  In
  ;; particular, the deadline entry may be dropped first (when the
  ;; deadline expires).
  ;;
  ;; Structurally:
  ;;   :selector-keys {select-key query-info ...}
  ;;   :deadlines {[deadline-ns select-key] query-info ...}

  (let [m (map->PuppetDBQueryMonitor
           {:exit (atom false)
            :selector (Selector/open)
            :queries (atom {:selector-keys {}
                            :deadlines (sorted-map-by compare-deadline-keys)})
            :terminate-query terminate-query})]
    (assoc m :thread
           (Thread. #(monitor-queries m terminate-query)
                    "pdb query monitor"))))

(defn start [{:keys [^Thread thread] :as monitor}]
  (.start thread)
  monitor)

(defn stop
  "Attempts to stop the monitor, waiting up to timeout-ms if provided,
  or forever.  Returns false if the monitor thread is still running,
  true otherwise.  May be called more than once.  When a true value is
  returned, all monitor activities should be finished."
  ([monitor] (stop monitor nil))
  ([{:keys [exit ^Selector selector ^Thread thread] :as _monitor} timeout-ms]
   (if-not (.isAlive thread)
     true
     (do
       (reset! exit true)
       (.wakeup selector)
       (if timeout-ms
         (.join thread timeout-ms)
         (.join thread))
       (not (.isAlive thread))))))

(defn- register-selector
  "Loops until the channel is registered with the selector while
  ignoring canceled key exceptions, which should only occur when a
  client is re-using the channel, and the previous query has called
  forget (and cancelled the key), and the main select loop hasn't
  removed it from the cancelled set yet.  Returns the new
  SelectionKey."
  [channel selector ops]
  (or (try
        (.register ^SelectableChannel channel selector ops)
        (catch CancelledKeyException _))
      (do
        (Thread/sleep 10)
        (recur channel selector ops))))

(defn stop-query-at-deadline-or-disconnect
  [{:keys [^Selector selector queries ^Thread thread] :as _monitor}
   id ^SelectableChannel channel deadline-ns db]
  (when-not (and (number? deadline-ns) (not (neg? deadline-ns)))
    (throw (bad-query-ex "Deadline is not a nonnegative number")))
  (if-not (.isAlive thread)
    (log/error "Query monitor thread not running when registering query (please report)")
    (let [select-key (register-selector channel selector SelectionKey/OP_READ)
          info {:query-id id
                :selection-key select-key
                :deadline-ns deadline-ns
                :db db
                :pg-pid (atom nil)
                :terminated nil}]
      (swap! queries
             (fn [{:keys [selector-keys] :as prev}]
               ;; Every query channel must currently be unique since it
               ;; determines the key.
               (when-let [{:keys [query-id]} (selector-keys select-key)]
                 (throw (Exception. (str "Existing query " (pr-str query-id)
                                         " has same channel as " (pr-str id)))))
               (-> prev
                   (update :selector-keys assoc select-key info)
                   (update :deadlines assoc [deadline-ns select-key] info))))
      (.wakeup selector) ;; to recompute deadline and start watching select-key
      select-key)))

(defn register-pg-pid
  [{:keys [queries ^Thread thread] :as _monitor} select-key pid]
  (if-not (.isAlive thread)
    (log/error "Query monitor thread not running when registering pg pid (please report)")
    (let [{:keys [selector-keys]} @queries
          {:keys [pg-pid] :as _info} (selector-keys select-key)]
      ;; Whole entry might not exist if the client has disconnected.
      (when pg-pid
        (swap! pg-pid (fn [prev]
                        (when prev
                          (throw
                           (Exception. (str "Postgres PID already registered: " prev))))
                        pid))))))

(defn forget-pg-pid [{:keys [queries ^Thread thread] :as _monitor} select-key]
  (if-not (.isAlive thread)
    (log/error "Query monitor thread not running when forgetting pg pid (please report)")
    (let [{:keys [selector-keys]} @queries
          {:keys [pg-pid] :as _info} (selector-keys select-key)]
      ;; Whole entry might not exist if the client has disconnected.
      (when pg-pid
        (swap! pg-pid
               (fn [prev]
                 (when-not prev
                   (throw (Exception. "No registered postgres PID to forget")))
                 nil))))))

(defn forget
  "Causes the monitor to forget the query specified by the select-key.
  Returns true if that succeeds, or ::timeout if the process does not
  complete within two seconds.  Repeated calls for the same query will
  not crash.  After a call for a given key, the monitor will have
  forgotten about it, but the final disposition of that query is
  undefined, i.e. it might or might not have been killed successfully.
  Calling this for a query that has already been forgotten is
  not an error (simplifies some error handling)."
  [{:keys [queries ^Selector selector ^Thread thread] :as _monitor}
   ^SelectionKey select-key]
  ;; NOTE: This is one of the few operations that races with the
  ;; monitor loop, so it must coordinate carefully.
  (if-not (.isAlive thread)
    (log/error "Query monitor thread not running when forgetting query (please report)")
    (let [maybe-await-termination (atom nil)]
      (swap! queries
             (fn [{:keys [selector-keys] :as state}]
               (if-let [{:keys [deadline-ns terminated] :as _info}
                        (selector-keys select-key)]
                 (do
                   (reset! maybe-await-termination terminated)
                   (drop-query state [deadline-ns select-key]))
                 state)))
      (.cancel select-key)
      (.wakeup selector) ;; clear out the cancelled keys (see stop-query-at-...)
      (if (= ::timeout (some-> @maybe-await-termination (deref 2000 ::timeout)))
        ::timeout
        true))))

(set! *warn-on-reflection* warn-on-reflection-orig)
