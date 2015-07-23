(ns puppetlabs.puppetdb.testutils.db
  (:require [clojure.java.jdbc.deprecated :as sql]
            [puppetlabs.puppetdb.testutils :refer [clear-db-for-testing! test-db]]))

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

(def ^:dynamic *db-spec* nil)

(defn insert-map [data]
  (apply (partial sql/insert-values :test [:key :value]) data))

(defn with-antonym-test-database
  [function]
  (sql/with-connection (test-db)
    (clear-db-for-testing!))
  (binding [*db-spec* (test-db)]
    (sql/with-connection *db-spec*
      (sql/transaction
       (sql/create-table :test
                         [:key   "VARCHAR(256)" "PRIMARY KEY"]
                         [:value "VARCHAR(256)" "NOT NULL"])
       (insert-map antonym-data))
      (function))))
