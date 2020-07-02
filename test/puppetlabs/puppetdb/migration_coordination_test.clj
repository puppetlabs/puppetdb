(ns puppetlabs.puppetdb.migration-coordination-test
  (:require
   [clojure.java.jdbc :as sql]
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.command.constants :as cmd-consts]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.scf.migrate :as migrate :refer [desired-schema-version]]
   [puppetlabs.puppetdb.testutils :refer [default-timeout-ms]]
   [puppetlabs.puppetdb.testutils.cli :refer [example-report]]
   [puppetlabs.puppetdb.testutils.db :as tdb
    :refer [*db*
            clear-db-for-testing!
            test-env
            with-test-db
            with-unconnected-test-db]]
   [puppetlabs.puppetdb.testutils.services :as svc-utils
    :refer [call-with-puppetdb-instance create-temp-config]]
   [puppetlabs.puppetdb.time :refer [now to-timestamp]])
  (:import
   (clojure.lang ExceptionInfo)
   (org.postgresql.util PSQLException)))

(deftest schema-mismatch-causes-new-connections-to-throw-expected-errors
  (doseq [db-upgraded? [true false]]
    (with-unconnected-test-db
       (call-with-puppetdb-instance
       (-> (create-temp-config)
           (assoc :database *db*)
           ;; Allow client connections to timeout more quickly to speed test
           (assoc-in [:database :connection-timeout] 300)
           (assoc-in [:database :gc-interval] 0))
       (fn []
         (jdbc/with-transacted-connection *db*
           ;; Simulate either the db or pdb being upgraded before the other.
           ;; This is added after initial startup and should cause any future
           ;; connection attempts to fail the HikariCP connectionInitSql check
           (if db-upgraded?
             (jdbc/insert! :schema_migrations {:version (inc desired-schema-version)
                                               :time (to-timestamp (now))})
             ;; removing the most recent schema version makes the connectionInitSql
             ;; check think the database doesn't have the correct migration applied
             (jdbc/delete! :schema_migrations ["version = ?" desired-schema-version])))

         ;; Kick out any existing connections belonging to the test db user. This
         ;; will cause HikariCP to create new connections which should all error
         (jdbc/with-transacted-connection
           (tdb/db-admin-config (tdb/subname->validated-db-name (:subname *db*)))
           (jdbc/disconnect-db-role (jdbc/current-database) (:user *db*)))

         (loop [retries 0]
           ;; Account for a race condition where connnections kicked out of PG by
           ;; the command above aren't yet cleaned from the pool. If Hikari attempts to
           ;; use these stale connections the error message in any resp will say the
           ;; connection was closed due to an administrator command which isn't what
           ;; we want to test. By allowing time for cleanup we can test that the
           ;; expected error message about a migration mismatch is present
           (let [ex (is (thrown? Exception
                                 (svc-utils/get-or-throw
                                  (svc-utils/query-url-str "/facts"))))
                 resp (-> ex ex-data :response)
                 err-msg (if db-upgraded?
                           "ERROR: Please upgrade PuppetDB"
                           "ERROR: Please run PuppetDB with the migrate option set to true")]
             (is (= 500 (:status resp)))
             (cond
               (some? (re-find (re-pattern err-msg) (:body resp))) :found
               (> retries 10) (throw (Exception. "Unable to find expected migration level error"))
               :else
               (do
                 (Thread/sleep 100)
                 (recur (inc retries))))))

         ;; Check that cmd submission isn't possible once migration level has been updated.
         ;; In this case the migration specific error doesn't propagate up to the cmd client
         ;; but is seen later in the logs during cmd retries. The client only receives a 503
         ;; service unavailable response in this case
         (loop [retries 0]
           (let [ex (is (thrown? Exception
                                 (svc-utils/sync-command-post
                                  (svc-utils/pdb-cmd-url)
                                  "foo.1"
                                  "store report"
                                  cmd-consts/latest-report-version
                                  (assoc example-report :certname "foo.1"))))]
             (cond
               (= 503 (-> ex ex-data :response :status)) :found
               (> retries 10) (throw (Exception. "Unable to find expected cmd submission status"))
               :else
               (do
                 (Thread/sleep 100)
                 (recur (inc retries)))))))))))

(deftest migrator-evicts-non-migrators-and-blocks-connections
  (with-test-db
    (clear-db-for-testing!)
    (let [deref-or-die #(when-not (deref % default-timeout-ms nil)
                          (throw
                           (ex-info "test promise deref timed out"
                                    {:kind ::migrator-evicts-non-migrators-and-blocks-connections})))
          config (assoc (create-temp-config) :database *db*)
          sleep-ex (promise)
          connect-ex (promise)
          finished-migrations (promise)
          hold-migrations #(do
                             (deliver finished-migrations true)
                             (deref-or-die connect-ex))]
      (future
        ;; Pretend to be a non-migrator -- sleep so we'll be connected
        ;; when the migrator evicts everyone.
        (try
          ;; Note: I think this may keep sleeping, even after the
          ;; connection is closed if something goes wrong.
          (jdbc/do-commands "select pg_sleep(60);")
          (catch Throwable ex
            (deliver sleep-ex ex)
            (throw ex))))

      (future
        (deref-or-die finished-migrations)
        ;; Try a new connection as the non-migrator to make sure it's
        ;; rejected.  This must be done from outside the pending
        ;; migration transaction.
        (try
          (sql/query *db* ["select certname from certnames"])
          (catch Exception ex
            (deliver connect-ex ex)
            (throw ex))))

      ;; Add a dummy migration so the migrator will run it.
      (with-redefs [migrate/migrations (assoc migrate/migrations
                                              (inc (apply max (keys migrate/migrations)))
                                              (constantly true))
                    migrate/note-migrations-finished hold-migrations]
        (call-with-puppetdb-instance
         config
         (fn []
           ;; Wait for the sleeping non-migrator to be ejected
           (deref sleep-ex default-timeout-ms nil)
           (deref connect-ex default-timeout-ms nil))))

      ;; By now we either timed out above, or exceptions are available
      (let [ex (deref sleep-ex 0 nil)
            report-ex (fn [x]
                        (binding [*out* *err*]
                          (println "Unexpected" (class x) "exception:")
                          (println x)))]
        ;; Newer versions of clojure.jdbc wrap the sql exception with ex-info.
        (is (= ExceptionInfo (class ex)))
        (if-not (= ExceptionInfo (class ex))
          (report-ex ex)
          (let [cause (:rollback (ex-data ex))]
            (is (= PSQLException (class cause)))
            (if-not (= PSQLException (class cause))
              (report-ex cause)
              ;; i.e. "This connection has been closed"
              (is (= "08003" (.getSQLState cause)))))))

      (let [ex (deref connect-ex 0 nil)]
        (is (= PSQLException (class ex)))
        ;; i.e. "User does not have CONNECT privilege"
        (is (= "42501" (some-> ex .getSQLState))))

      ;; Ensure that all the objects created (here by the initial
      ;; migrations) are owned by the normal user, not the migrator.
      (jdbc/with-db-connection *db*
        (is (= "pdb_test"
               (-> "select tableowner from pg_tables where tablename = 'factsets'"
                   jdbc/query-to-vec
                   first
                   :tableowner)))))))
