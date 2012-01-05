(ns com.puppetlabs.cmdb.testutils)

(defn test-db
  "Return a map of connection attrs for an in-memory database"
  []
  {:classname "org.hsqldb.jdbcDriver"
   :subprotocol "hsqldb"
   :subname     (str "mem:"
                     (java.util.UUID/randomUUID)
                     ";shutdown=true;hsqldb.tx=mvcc;sql.syntax_pgs=true")})
