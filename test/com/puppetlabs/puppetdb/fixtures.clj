(ns com.puppetlabs.puppetdb.fixtures
  (:require [clojure.java.jdbc :as sql]
            [com.puppetlabs.puppetdb.http.server :as server]
            [com.puppetlabs.jdbc :as pjdbc]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-log-output]])
  (:use [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db with-test-broker]]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]))

(def ^:dynamic *db* nil)
(def ^:dynamic *mq* nil)
(def ^:dynamic *conn* nil)
(def ^:dynamic *app* nil)

(defn init-db [db read-only?]
  (binding [*db* db]
    (do
      (sql/with-connection *db*
        (sql/transaction
         (clear-db-for-testing!)
         (migrate!)))
      (pjdbc/pooled-datasource (assoc db :read-only? read-only?)))))

(defn with-test-db
  "A fixture to start and migrate a test db before running tests."
  [f]
  (binding [*db* (test-db)]
    (sql/with-connection *db*
      (clear-db-for-testing!)
      (migrate!)
      (f))))

(defn with-test-mq
  "A fixture to start an MQ broker, making the broker information available as
  `*mq*` and the connection as `*conn*`."
  [f]
  (with-test-broker "test" conn
    (binding [*mq*   {:connection-string "vm://test"
                      :endpoint          "com.puppetlabs.puppetdb.commands"}
              *conn* conn]
      (f))))

(defn with-http-app
  "A fixture to build an HTTP app and make it available as `*app*` within
  tests. This will provide the `*db*` and `*mq*` to the app as globals if they
  are available. Note this means this fixture should be nested _within_
  `with-test-db` or `with-test-mq`."
  ([f]
     (with-http-app {} f))
  ([globals-overrides f]
     (binding [*app* (server/build-app
                      :globals (merge
                                {:scf-read-db          *db*
                                 :scf-write-db         *db*
                                 :command-mq           *mq*
                                 :resource-query-limit 20000
                                 :event-query-limit    20000
                                 :product-name         "puppetdb"}
                                globals-overrides))]
       (f))))

(defn create-db-map []
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (str "mem:"
                     (java.util.UUID/randomUUID)
                     ";hsqldb.tx=mvcc;sql.syntax_pgs=true")})

(defn with-test-logging-silenced
  "A fixture to temporarily redirect all logging output to an atom, rather than
  to the usual ConsoleAppender.  Useful for tests that are intentionally triggering
  error conditions, to prevent them from cluttering up the test output with log
  messages."
  [f]
  (with-log-output logs
    (f)))
