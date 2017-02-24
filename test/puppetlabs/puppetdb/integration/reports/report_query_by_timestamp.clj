(ns puppetlabs.puppetdb.integration.reports.report-query-by-timestamp
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now]]
            [clj-time.format :as tfmt]
            [puppetlabs.puppetdb.cheshire :as json]))

(def date-formatter (tfmt/formatters :date-time))

(defn unparse' [date]
  (tfmt/unparse date-formatter date))

(deftest ^:integration query-by-timestamp
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server-as "my_puppetserver" [pdb] {})]
    (let [start-time (now)]
      ;; Sleep 1 ms so that we are sure to get a start-time/end-time
      ;; before/after the puppet run
      (Thread/sleep 1)
      (testing "Initial agent run, to populate puppetdb with data to query"
        (int/run-puppet-as "my_agent" ps pdb
                           (str "notify { \"hi\":"
                                "  message => \"Hi my_agent\" "
                                "}")))
      (Thread/sleep 1)
      (let [end-time (now)
            result (int/entity-query pdb "/events"
                                     ["and"
                                      ["=" "certname" "my_agent"]
                                      [">" "timestamp" (unparse' start-time)]
                                      ["<" "timestamp" (unparse' end-time)]])]
        (is (= (count result) 1))))))
