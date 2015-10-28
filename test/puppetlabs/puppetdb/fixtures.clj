(ns puppetlabs.puppetdb.fixtures
  (:import [java.io ByteArrayInputStream])
  (:require [clojure.java.jdbc :as sql]
            [clojure.test]
            [puppetlabs.puppetdb.http.server :as server]
            [puppetlabs.puppetdb.command :as dispatch]
            [puppetlabs.puppetdb.http.command :as command]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.schema :as pls]
            [puppetlabs.puppetdb.config :as conf]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.trapperkeeper.testutils.logging :refer [with-test-logging]]
            [clojure.tools.macro :as tmacro]
            [clojure.test :refer [join-fixtures use-fixtures]]
            [puppetlabs.puppetdb.testutils
             :refer [with-test-broker without-jmx]]
            [puppetlabs.puppetdb.testutils.db :as tdb]
            [puppetlabs.trapperkeeper.logging :refer [reset-logging]]
            [puppetlabs.puppetdb.scf.migrate :refer [migrate!]]
            [puppetlabs.puppetdb.middleware :as mid]))

(def ^:dynamic *db* nil)
(def ^:dynamic *mq* nil)
(def ^:dynamic *app* nil)
(def ^:dynamic *command-app* nil)

(defn init-db [db read-only?]
  (jdbc/with-db-connection db (migrate! db))
  (jdbc/pooled-datasource (assoc db :read-only? read-only?)))

(defn call-with-db-info-on-failure-or-drop
  "Calls (f), and then if there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  [db-config f]
  (let [before @clojure.test/*report-counters*]
    (try
      (f)
      (finally
        (let [after @clojure.test/*report-counters*]
          (if (and (= (:error before) (:error after))
                   (= (:fail before) (:fail after)))
            (jdbc/with-db-connection (tdb/db-admin-config)
              (jdbc/do-commands-outside-txn
               (format "drop database if exists %s"
                       (tdb/subname->validated-db-name (:subname db-config)))))
            (clojure.test/with-test-out
              (println "Leaving test database intact:" (:subname *db*)))))))))

(defmacro with-db-info-on-failure-or-drop
  "Evaluates body in the context of call-with-db-info-on-failure-or-drop."
  [db-config & body]  
  `(call-with-db-info-on-failure-or-drop ~db-config (fn [] ~@body)))

(defn with-db-metadata
  "A fixture to collect DB type and version information before a test."
  [f]
  (binding [*db* (tdb/create-temp-db)] ;; FIXME: do we really want a new DB?
    (with-db-info-on-failure-or-drop *db*
      (jdbc/with-db-connection *db*
        (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
          (f))))))

(defn call-with-test-db
  "Binds *db* to a clean, migrated test database, opens a connection
  to it, and calls (f).  If there are no clojure.tests failures or
  errors, drops the database, otherwise displays its subname."
  [f]
  (binding [*db* (tdb/create-temp-db)]
    (with-db-info-on-failure-or-drop *db*
      (jdbc/with-db-connection *db*
        (with-redefs [sutils/db-metadata (delay (sutils/db-metadata-fn))]
          (f))))))

(defmacro with-test-db [& body]
  `(call-with-test-db (fn [] ~@body)))

(defn without-db-var
  "Binds the java.jdbc dtabase connection to nil. When running a unit
   test using `call-with-test-db`, jint/*db* will be bound. If the routes
   being tested don't explicitly bind the db connection, it will use
   one bound in call-with-test-db. This causes a problem at runtime that
   won't show up in the unit tests. This fixture can be used around
   route testing code to ensure that the route has it's own db
   connection."
  [f]
  (binding [jdbc/*db* nil]
    (f)))

(defn with-test-mq
  "Calls f after starting an embedded MQ broker that will be available
  for the duration of the call via *mq*.  JMX will be disabled."
  [f]
  (without-jmx
   (with-test-broker "test" connection
     (binding [*mq* {:connection connection}]
       (f)))))

(defn call-with-command-app
  "A fixture to build a Command app and make it available as `*command-app*` within
  tests. This will provide the `*mq*` to the app as a global if it
  is available. Note this means this fixture should be nested _within_
  `with-test-mq`."
  ([f]
   (binding [*command-app* (mid/wrap-with-puppetdb-middleware
                            (command/command-app
                             (fn [] {})
                             (partial #'dispatch/do-enqueue-raw-command
                                      (:connection *mq*)
                                      conf/default-mq-endpoint)
                             (fn [] nil))
                            nil)]
     (f))))

(defn call-with-http-app
  "Builds an HTTP app and make it available as *app* during the
  execution of (f)."
  [f]
  (let [get-shared-globals (constantly {:scf-read-db *db*
                                        :scf-write-db *db*
                                        :url-prefix ""})]
    (binding [*app* (mid/wrap-with-puppetdb-middleware
                     (server/build-app get-shared-globals)
                     nil)]
      (f))))

(defmacro with-http-app
  [& body]
  `(call-with-http-app (fn [] ~@body)))

(defn defaulted-write-db-config
  "Defaults and converts `db-config` from the write database INI
  format to the internal write database format"
  [db-config]
  (pls/transform-data conf/write-database-config-in
                      conf/write-database-config-out db-config))

(defn defaulted-read-db-config
  "Defaults and converts `db-config` from the read-database INI format
  to the internal read database format"
  [db-config]
  (pls/transform-data conf/database-config-in
                      conf/database-config-out db-config))

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
                :product-name         "puppetdb"}
      :body (ByteArrayInputStream. (.getBytes body "utf8"))}))

(defmacro defixture
  "Defs a var `name` that is the composed fixtures for the ns and then uses those fixtures.

   Example:

     (fixt/defixture super-fixture :each fixt/call-with-test-db fixt/with-http-app)

     Which is equivalent to:

     (use-fixtures :each fixt/call-with-test-db fixt/with-http-app)

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
