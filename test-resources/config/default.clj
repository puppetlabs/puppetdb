;; This is the default test configuration file.  If you would like to override
;; some of the settings herein, simply make a copy of this file and name it
;; `local.clj`.  It will be loaded instead of this one.  (`local.clj` is
;; included in the `.gitignore` file, so you don't have to worry about
;; accidentally committing your local changes.)

(defn test-db-config
  "Return a map of connection attrs for the test database"
  []
  {:classname   "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (str "mem:"
                  (java.util.UUID/randomUUID)
                  ";shutdown=true;hsqldb.tx=mvcc;sql.syntax_pgs=true")})

;; here is a sample test-db-config function for use with postgres
;(defn test-db-config
;  "Return a map of connection attrs for the test database"
;  []
;  {:classname   "org.postgresql.Driver"
;   :subprotocol "postgresql"
;   :subname     "//localhost:5432/puppetdb_test"
;   :user        "puppetdb"
;   :password    "puppet"
;   })

