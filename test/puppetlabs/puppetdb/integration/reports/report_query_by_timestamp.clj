(ns puppetlabs.puppetdb.integration.reports.report-query-by-timestamp
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [clj-time.core :refer [now days plus]]
            [puppetlabs.puppetdb.cheshire :as json]))

(defn add-2-days [timestamp]
  (plus timestamp (days 2)))

(deftest ^:integration query-by-timestamp
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]

    (let [start-time (now)
          query-with-one-ts (str "events { certname = 'my_agent' "
                                 " and timestamp > '%s' "
                                 "}")
          query-with-two-ts (str "events { certname = 'my_agent'"
                                 " and timestamp > '%s'"
                                 " and timestamp < '%s' "
                                 "}")]
      ;; Sleep 1 ms so that we are sure to get a start-time/end-time
      ;; before/after the puppet run
      (Thread/sleep 1)

      (testing "No data should be found since a puppet run hasn't happened"
        (is (= 0
               (->> start-time
                    int/query-timestamp-str
                    (format query-with-one-ts)
                    (int/pql-query pdb)
                    count))))

      (testing "Initial agent run, to populate puppetdb with data to query"
        (int/run-puppet-as "my_agent" ps pdb
                           (str "notify { 'hi':"
                                "  message => 'Hi my_agent' "
                                "}"))
        (is (= 1
               (->> start-time
                    int/query-timestamp-str
                    (format query-with-one-ts)
                    (int/pql-query pdb)
                    count)))

        (is (= 0
               (->> start-time
                    add-2-days
                    int/query-timestamp-str
                    (format query-with-one-ts)
                    (int/pql-query pdb)
                    count))))

      (Thread/sleep 1)

      (testing "queries with a range"

        (let [end-time (now)]
          (is (= 1
                 (->> (format query-with-two-ts
                              (int/query-timestamp-str start-time)
                              (int/query-timestamp-str end-time))
                      (int/pql-query pdb)
                      count)))
          (is (= 0
                 (->> (format query-with-two-ts
                              (add-2-days start-time)
                              (add-2-days end-time))
                      (int/pql-query pdb)
                      count))))))))
