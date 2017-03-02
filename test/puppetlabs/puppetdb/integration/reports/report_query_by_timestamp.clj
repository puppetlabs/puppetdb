(ns puppetlabs.puppetdb.integration.reports.report-query-by-timestamp
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now days plus]]
            [puppetlabs.puppetdb.cheshire :as json]))

(defn add-2-days [timestamp]
  (-> timestamp
      (plus (days 2))
      int/query-timestamp-str))

(deftest ^:integration query-by-timestamp
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (let [start-time (now)]
      ;; Sleep 1 ms so that we are sure to get a start-time/end-time
      ;; before/after the puppet run
      (Thread/sleep 1)

      (testing "No data should be found since a puppet run hasn't happened"
        (is (= 0
               (count
                (int/entity-query pdb "/events"
                                  ["and"
                                   ["=" "certname" "my_agent"]
                                   [">" "timestamp" (int/query-timestamp-str start-time)]])))))

      (testing "Initial agent run, to populate puppetdb with data to query"
        (int/run-puppet-as "my_agent" ps pdb
                           (str "notify { 'hi':"
                                "  message => 'Hi my_agent' "
                                "}"))
        (is (= 1
               (count
                (int/entity-query pdb "/events"
                                  ["and"
                                   ["=" "certname" "my_agent"]
                                   [">" "timestamp" (int/query-timestamp-str start-time)]]))))

        (is (= 0
               (count
                (int/entity-query pdb "/events"
                                  ["and"
                                   ["=" "certname" "my_agent"]
                                   [">" "timestamp" (add-2-days start-time)]])))))

      (Thread/sleep 1)

      (testing "queries with a range"

        (let [end-time (now)]
          (is (= 1
                 (count
                  (int/entity-query pdb "/events"
                                    ["and"
                                     ["=" "certname" "my_agent"]
                                     [">" "timestamp" (int/query-timestamp-str start-time)]
                                     ["<" "timestamp" (int/query-timestamp-str end-time)]]))))
          (is (= 0
                 (count
                  (int/entity-query pdb "/events"
                                    ["and"
                                     ["=" "certname" "my_agent"]
                                     [">" "timestamp" (add-2-days start-time)]
                                     ["<" "timestamp" (add-2-days end-time)]])))))))))
