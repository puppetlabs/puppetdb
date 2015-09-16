(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc :as sql]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.testutils :refer [clear-db-for-testing! test-db]]))

(def ^:dynamic *db-spec* nil)

(def antonym-data {"absence"    "presence"
                   "abundant"   "scarce"
                   "accept"     "refuse"
                   "accurate"   "inaccurate"
                   "admit"      "deny"
                   "advance"    "retreat"
                   "advantage"  "disadvantage"
                   "alive"      "dead"
                   "always"     "never"
                   "ancient"    "modern"
                   "answer"     "question"
                   "approval"   "disapproval"
                   "arrival"    "departure"
                   "artificial" "natural"
                   "ascend"     "descend"
                   "blandness"  "zest"
                   "lethargy"   "zest"})

(defn insert-map [data]
  (apply (partial jdbc/insert! :test [:key :value]) data))

(defn with-antonym-test-database
  [function]
  (let [db (test-db)]
    (binding [*db-spec* db]
      (jdbc/with-db-connection db
        (clear-db-for-testing!)
        (jdbc/with-db-transaction []
          (jdbc/do-commands
           (sql/create-table-ddl :test
                                 [:key "VARCHAR(256)" "PRIMARY KEY"]
                                 [:value "VARCHAR(256)" "NOT NULL"]))
          (insert-map antonym-data))
        (function)))))
