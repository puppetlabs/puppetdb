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
  single monitor thread.  That thread's main loop handles all data
  \"expiration\" -- other operations, which may run concurrently, only
  set the info's :forget to true.

  No operations should block forever; they should all eventually (in
  some cases customizably) time out.

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

  The terminated promise and :forget value coordinate between the
  monitor and attempts to remove (forget) a query.  The arrangement is
  intended to make sure that the attempt to forget doesn't compete
  with an in-flight termination (otherwise the termination might kill
  a pg worker that's no longer associated with the query).

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
   [puppetlabs.puppetdb.scf.storage-utils :refer [db-metadata]]
   [puppetlabs.puppetdb.time :refer [ephemeral-now-ns]]
   [puppetlabs.puppetdb.utils :refer [with-monitored-execution]]
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
                   (map #(select-keys % [:query-id :deadline-ns :pg-pid
                                         :terminated :forget]))
                   (map #(assoc % :remaining-sec
                                (-> (- (:deadline-ns %) (ephemeral-now-ns))
                                    (/ 1000000000)
                                    double))))})

(defn- terminate-query
  "Terminates any pg-pid connection provided by the info unless :forget
  is true.  Always delivers true to any terminated promise."
  [{:keys [forget query-id db pg-pid terminated] :as _info} what]
  (assert pg-pid)
  (try
    (when-not forget
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
                            what query-id (pr-str rows)))))))
    (finally
      (when terminated
        (deliver terminated true)))))

(defn- next-expired-query!
  "Removes the next expired query (if any) from queries and returns
  the info for that query and the next ns deadline (after that query)
  as [info deadline].  Returns a false value if there's nothing
  expired."
  [queries now]
  ;; Process just one query at a time so that each :terminate
  ;; wait/timeout will be independent.
  (let [{:keys [deadlines] :as cur} @queries]
    ;; Grab the earliest deadline, if any.
    (when-let [[[[deadline-ns skey :as deadkey]
                 {:keys [forget] :as info}]]
               (seq deadlines)]
      ;; Use compare-and-set! (not swap!) so we can return the next deadline.
      (if-not (<= deadline-ns now)
        [nil deadline-ns nil]
        (if forget
          (let [new (-> cur
                        (update :deadlines dissoc deadkey)
                        (update :selector-keys dissoc skey))]
            (compare-and-set! queries cur new) ;; if we lose, recur tries again
            (recur queries now))
          ;; Swap in terminated first, so that we know the pg-pid
          ;; won't change to some other query's, at least until we
          ;; finish or the query thread's deref times out.
          (let [info (assoc info :terminated (promise))
                new (-> cur
                        (update :deadlines dissoc deadkey)
                        (update :selector-keys assoc deadkey info))]
            (if (compare-and-set! queries cur new)
              [info (-> cur :deadlines ffirst) (second deadkey)]
              (recur queries now))))))))

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
          (stop-query info "expired")
          (.cancel ^SelectionKey select-key)
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
  [^ReadableByteChannel chan buf]
  ;; Various ssumptions here:
  ;;   - transport (chan) will be non-blocking
  ;;   - everyone, including jetty, etc. won't miss discarded bytes
  ;;   - transport will return -1 or exception when client is gone
  ;;   - typically there won't be any bytes, and read will return 0
  (try
    (= -1 (.read chan buf))
    (catch ClosedChannelException _ true)))

(defn- stop-abandoned!
  "Attempts to terminate every selected query whose client channel has
  closed, and then removes them from queries.  The buf (likely a
  ByteBuffer) is (re)used for a test client read.  Larger sizes will
  drain any unexpected client data faster.  In the longer run, for
  puppetdb, we could consider treating data from the client as an
  error."
  [queries selected stop-query buf]
  (doseq [^SelectionKey select-key selected]
    (when (disconnected? (.channel select-key) buf)
      (.cancel select-key)
      (let [info (-> @queries :selector-keys (get select-key))]
        (when-not (:forget info)
          (log/warn (trs "Unexpected PDBQuery:{0} client channel event: {1}"
                         (:query-id info)
                         (pr-str (describe-key select-key))))
          (stop-query info "abandoned")))))
  (swap! queries
         (fn [{:keys [selector-keys] :as cur}]
           (let [dead-keys (mapv (fn [sk]
                                   [(-> selector-keys (get sk) :deadline-ns)
                                    sk])
                                 selected)]
             (-> cur
                 (update :deadlines #(apply dissoc % dead-keys))
                 (update :selector-keys #(apply dissoc % selected)))))))

(defn- deadline->select-timeout
  "Returns selector timeout ms given an ephemeral deadline-ns."
  [deadline-ns]
  (cond
    (= ##Inf deadline-ns) 0 ;; block select forever
    (= ##-Inf deadline-ns) 1 ;; expired; unblock select as fast as we can
    :else (max 1 (int (/ (- deadline-ns (ephemeral-now-ns))
                         ns-per-ms)))))

(defn- monitor-queries [{:keys [exit queries ^Selector selector] :as _monitor}
                        terminate-query
                        on-fatal-error]
  ;; We depend on the fact that any new queries will wake us
  ;; up (i.e. wrt possible deadline advancements).
  (with-monitored-execution on-fatal-error
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
          (trs "Query monitor shutting down"))
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
  [& {:keys [terminate-query on-fatal-error]
      :or {terminate-query puppetlabs.puppetdb.query.monitor/terminate-query
           on-fatal-error identity}}]

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
           (Thread. #(monitor-queries m terminate-query on-fatal-error)
                    "pdb query monitor"))))

(defn start [{:keys [^Thread thread] :as monitor}]
  (assert (not (.isAlive thread)))
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
  (assert (.isAlive thread))
  (assert (instance? SelectableChannel channel))
  (let [select-key (register-selector channel selector SelectionKey/OP_READ)
        info {:query-id id
              :selection-key select-key
              :deadline-ns deadline-ns
              :db db
              :pg-pid (atom nil)
              :terminated nil
              :forget false}]
    (assert deadline-ns)
    (swap! queries
           (fn [{:keys [selector-keys] :as prev}]
             ;; Every query channel must currently be unique since it
             ;; determines the key.
             (when-let [{:keys [query-id]} (selector-keys select-key)]
               (throw (Exception. (str "Existing query " (pr-str query-id)
                                       " has same channel as " (pr-str id)))))
             (-> prev
                 (update :selector-keys assoc select-key info)
                 (update :deadlines conj [[deadline-ns select-key] info]))))
    (.wakeup selector) ;; to recompute deadline and start watching select-key
    select-key))

(defn register-pg-pid
  [{:keys [queries ^Thread thread] :as _monitor} select-key pid]
  (assert (.isAlive thread))
  (let [{:keys [selector-keys]} @queries
        {:keys [pg-pid] :as _info} (selector-keys select-key)]
    ;; Whole entry might not exist if the client has disconnected.
    (when pg-pid
      (swap! pg-pid (fn [prev] (assert (not prev)) pid)))))

(defn forget-pg-pid [{:keys [queries thread] :as _monitor} select-key]
  (assert (.isAlive ^Thread thread))
  (let [{:keys [selector-keys]} @queries
        {:keys [pg-pid] :as _info} (selector-keys select-key)]
    ;; Whole entry might not exist if the client has disconnected.
    (when pg-pid
      (swap! pg-pid (fn [prev] (assert prev) nil)))))

(defn forget
  "Causes the monitor to forget the query specified by the select-key.
  Returns true if that succeeds, or ::timeout if the process does not
  complete within two seconds.  Repeated calls for the same query will
  not crash.  After a call for a given key, the monitor will have
  forgotten about it, but the final disposition of that query is
  undefined, i.e. it might or might not have been killed
  successfully."
  [{:keys [queries ^Selector selector ^Thread thread] :as _monitor}
   ^SelectionKey select-key]
  (assert (.isAlive thread))
  (let [maybe-await-termination (atom nil)]
    (swap! queries
           (fn [{:keys [selector-keys] :as state}]
             (if-let [{:keys [deadline-ns terminated] :as info}
                      (selector-keys select-key)]
               (let [info (assoc info :forget true)]
                 (reset! maybe-await-termination terminated)
                 (-> state
                     (update :selector-keys assoc select-key info)
                     (update :deadlines assoc [deadline-ns select-key] info)))
               state)))
    (.cancel select-key)
    (.wakeup selector) ;; clear out the cancelled keys (see stop-query-at-...)
    (if (= ::timeout (some-> @maybe-await-termination (deref 2000 ::timeout)))
      ::timeout
      true)))

(set! *warn-on-reflection* warn-on-reflection-orig)
