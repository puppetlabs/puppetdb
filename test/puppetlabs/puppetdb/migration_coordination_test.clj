(ns puppetlabs.puppetdb.migration-coordination-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.command.constants :as cmd-consts]
   [puppetlabs.puppetdb.jdbc :as jdbc]
   [puppetlabs.puppetdb.scf.migrate :refer [desired-schema-version]]
   [puppetlabs.puppetdb.testutils.db :as tdb :refer [*db*]]
   [puppetlabs.puppetdb.testutils.services :as svc-utils]
   [puppetlabs.puppetdb.time :refer [now to-timestamp]]
   [puppetlabs.puppetdb.testutils.cli :refer [example-report]]))

(deftest schema-mismatch-causes-new-connections-to-throw-expected-errors
  (doseq [db-upgraded? [true false]]
    (tdb/with-test-db
      (svc-utils/call-with-puppetdb-instance
       (-> (svc-utils/create-temp-config)
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
           (tdb/db-admin-config)
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
                           "ERROR: Please run PuppetDB with the migrate\\? option set to true")]
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
