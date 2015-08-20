(ns puppetlabs.puppetdb.fixtures
  (:import [java.io ByteArrayInputStream])
  (:require [clojure.java.jdbc.deprecated :as sql]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.command :as dispatch]
            [puppetlabs.puppetdb.http.command :as command]
            [puppetlabs.puppetdb.jdbc :as pjdbc]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.config :as cfg]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [clojure.tools.macro :as tmacro]
            [clojure.test :refer [join-fixtures use-fixtures]]
            [puppetlabs.puppetdb.testutils
             :refer [clear-db-for-testing! test-db with-test-broker without-jmx]]
            [puppetlabs.trapperkeeper.logging :refer [reset-logging]]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]))

(def ^:dynamic *db* nil)
(def ^:dynamic *mq* nil)
(def ^:dynamic *app* nil)
(def ^:dynamic *command-app* nil)

(defn init-db [db read-only?]
  (binding [*db* db]
    (do
      (sql/with-connection *db*
        (sql/transaction
         (clear-db-for-testing!)
         (migrate! *db*)))
      (pjdbc/pooled-datasource (assoc db :read-only? read-only?)))))

(defn with-db-metadata
  "A fixture to collect DB type and version information before a test."
  [f]
  (binding [*db* (test-db)]
    (sql/with-connection *db*
      (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
        (f)))))

(defn with-test-db
  "A fixture to start and migrate a test db before running tests."
  [f]
  (binding [*db* (test-db)]
    (sql/with-connection *db*
      (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
        (clear-db-for-testing!)
        (migrate! *db*)
        (f)))))

(defn without-db-var
  "Binds the java.jdbc dtabase connection to nil. When running a unit
   test using `with-test-db`, jint/*db* will be bound. If the routes
   being tested don't explicitly bind the db connection, it will use
   one bound in with-test-db. This causes a problem at runtime that
   won't show up in the unit tests. This fixture can be used around
   route testing code to ensure that the route has it's own db
   connection."
  [f]
  (binding [sql/*db* nil]
    (f)))

(defn with-test-mq
  "Calls f after starting an embedded MQ broker that will be available
  for the duration of the call via *mq*.  JMX will be disabled."
  [f]
  (without-jmx
   (with-test-broker "test" connection
     (binding [*mq* {:connection connection
                     :endpoint "puppetlabs.puppetdb.commands"}]
       (f)))))

(defn with-command-app
  "A fixture to build a Command app and make it available as `*command-app*` within
  tests. This will provide the `*mq*` to the app as a global if it
  is available. Note this means this fixture should be nested _within_
  `with-test-mq`."
  ([f]
   (binding [*command-app* (command/command-app
                            nil
                            (fn [] *mq*)
                            (fn [] {})
                            #'dispatch/do-enqueue-raw-command
                            (fn [] nil))]
     (f))))

(defn with-http-app
  "A fixture to build an HTTP app and make it available as `*app*` within
  tests. This will provide the `*db*` and `*mq*` to the app as globals if they
  are available. Note this means this fixture should be nested _within_
  `with-test-db` or `with-test-mq`."
  ([f]
   (with-http-app {} f))
  ([globals-overrides f]
   (binding [*app* (server/build-app nil
                                     #(merge {:scf-read-db *db*
                                              :scf-write-db *db*
                                              :command-mq *mq*
                                              :product-name "puppetdb"
                                              :url-prefix ""}
                                             globals-overrides))]
     (f))))

(defn defaulted-write-db-config
  "Defaults and converts `db-config` from the write database INI format to the internal
   write database format"
  [db-config]
  (pls/transform-data cfg/write-database-config-in cfg/write-database-config-out db-config))

(defn defaulted-read-db-config
  "Defaults and converts `db-config` from the read-database INI format to the internal
   read database format"
  [db-config]
  (pls/transform-data cfg/database-config-in cfg/database-config-out db-config))

(defn create-db-map
  "Returns a database connection map with a reference to a new in memory HyperSQL database"
  []
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
  (reset-logging)
  (with-test-logging
    (f)))

(defn internal-request
  "Create a ring request as it would look after passing through all of the
   application middlewares, suitable for invoking one of the api functions
   (where it assumes the middleware have already assoc'd in various attributes)."
  ([]
     (internal-request {}))
  ([params]
     (internal-request {} params))
  ([global-overrides params]
     {:params params
      :headers {"accept" "application/json"
                "content-type" "application/x-www-form-urlencoded"}
      :content-type "application/x-www-form-urlencoded"
      :globals (merge {:update-server "FOO"
                       :scf-read-db          *db*
                       :scf-write-db         *db*
                       :command-mq           *mq*
                       :product-name         "puppetdb"}
                      global-overrides)}))

(defn internal-request-post
  "A variant of internal-request designed to submit application/json requests
  instead."
  ([body]
     (internal-request-post body {}))
  ([body params]
     {:params params
      :headers {"accept" "application/json"
                "content-type" "application/json"}
      :content-type "application/json"
      :globals {:update-server "FOO"
                :scf-read-db          *db*
                :scf-write-db         *db*
                :command-mq           *mq*
                :product-name         "puppetdb"}
      :body (ByteArrayInputStream. (.getBytes body "utf8"))}))

(defmacro defixture
  "Defs a var `name` that is the composed fixtures for the ns and then uses those fixtures.

   Example:

     (fixt/defixture super-fixture :each fixt/with-test-db fixt/with-http-app)

     Which is equivalent to:

     (use-fixtures :each fixt/with-test-db fixt/with-http-app)

     but is also usable individually:

     (super-fixture
       (fn []
         ;; --> Do stuff, the drop the database at the end
       ))"
  [name & args]
  (let [[name [each-or-once & fixtures]] (tmacro/name-with-attributes name args)]
    `(do
       (def ~name (join-fixtures ~(vec fixtures)))
       (apply use-fixtures ~each-or-once ~(vec fixtures)))))
