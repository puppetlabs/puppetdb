(ns com.puppetlabs.puppetdb.fixtures
  (:require [clojure.java.jdbc :as sql]
            [com.puppetlabs.puppetdb.http.server :as server])
  (:use [com.puppetlabs.puppetdb.testutils :only [clear-db-for-testing! test-db with-test-broker]]
        [com.puppetlabs.testutils.logging :only [with-log-output]]
        [com.puppetlabs.puppetdb.scf.migrate :only [migrate!]]))

(def ^:dynamic *db* nil)
(def ^:dynamic *mq* nil)
(def ^:dynamic *conn* nil)
(def ^:dynamic *app* nil)

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
                                {:scf-db               *db*
                                 :command-mq           *mq*
                                 :event-query-limit    20000
                                 :product-name         "puppetdb"}
                                globals-overrides))]
       (f))))

(defn with-test-logging
  "A fixture to temporarily redirect all logging output to an atom, rather than
  to the usual ConsoleAppender.  Useful for tests that are intentionally triggering
  error conditions, to prevent them from cluttering up the test output with log
  messages."
  [f]
  (with-log-output logs
    (f)))
