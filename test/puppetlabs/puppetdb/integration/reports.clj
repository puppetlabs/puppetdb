(ns puppetlabs.puppetdb.integration.reports
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]
            [metrics.counters :as counters]
            [puppetlabs.puppetdb.cli.services :as pdb-services]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.time :refer [days now plus]]))

(defn add-2-days [timestamp]
  (plus timestamp (days 2)))

(deftest ^:integration basic-report-storage-and-querying
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server-as "my_puppetserver" [pdb] {})]
    (let [start-time (now)
          query-with-one-ts (str "events { certname = 'my_agent' "
                                 " and timestamp > '%s' "
                                 "}")
          query-with-two-ts (str "events { certname = 'my_agent'"
                                 " and timestamp > '%s'"
                                 " and timestamp < '%s' "
                                 "}")]
      ;; Ensure at least 1 ms has passed
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
                                "}")))

      (testing "event storage/querying"
        (let [result (int/pql-query pdb (format (str "events [old_value, new_value] "
                                                     "{ timestamp > '%s' "
                                                     " and certname = 'my_agent'"
                                                     " and resource_type = 'Notify'"
                                                     " and status = 'success'"
                                                     " and property ~ '^[Mm]essage$'"
                                                     " and message ~ 'Hi my_agent' "
                                                     "}")
                                                (int/query-timestamp-str start-time)))]
          (is (= [{:old_value "absent"
                   :new_value "Hi my_agent"}]
                 result))))

      (testing "populate of producer"
        (let [result (int/pql-query pdb "reports [producer] { certname = 'my_agent' }")]
          (is (= [{:producer "my_puppetserver"}]
                 result))))

      (testing "transaction_uuid storage/correlation"
        (let [[report] (int/pql-query pdb "reports { certname = 'my_agent' }")
              [catalog] (int/pql-query pdb "catalogs { certname = 'my_agent' }")]
          (is (:transaction_uuid report))
          (is (:transaction_uuid catalog))
          (is (= (:transaction_uuid report)
                 (:transaction_uuid catalog)))))

      (testing "querying for reports by timestamp"

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
                    count)))

        (Thread/sleep 1)

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

(defn get-href [pdb suffix]
  (-> pdb
      int/root-url-str
      (str suffix)
      svc-utils/get-ssl
      :body))

(deftest ^:integration metrics-and-logs-storage
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Initial agent run, to populate puppetdb with data to query"
      (int/run-puppet-as "my_agent" ps pdb
                         (str "notify { 'hi':"
                              "  message => 'Hi my_agent' "
                              "}")
                         {:extra-puppet-args ["--noop"]})

      ;; This is a bit weird as well; all "skipped" resources during a puppet
      ;; run will end up having events generated for them.  However, during a
      ;; typical puppet run there are a bunch of "Schedule" resources that will
      ;; always show up as skipped.  Here we filter them out because they're
      ;; not really interesting for this test.
      (let [result (int/pql-query pdb "reports { certname = 'my_agent' }")
            [event :as events] (remove #(= (:resource_type %) "Schedule")
                                       (int/pql-query pdb (format "events { report = '%s' }"
                                                                  (-> result first :hash))))]
        (are [x y] (= x y)
          1 (count events)
          true (:noop (first result))
          "Notify" (:resource_type event)
          "hi" (:resource_title event)
          "message" (:property event)
          "Hi my_agent" (:new_value event))))

    (testing "agent run without noop"
      (int/run-puppet-as "my_agent" ps pdb
                         (str "notify { 'hi':"
                              "  message => 'Hi my_agent' "
                              "}"))

      (let [[report] (int/pql-query pdb "reports { certname = 'my_agent' }")
            metrics (get-href pdb (get-in report [:metrics :href]))
            logs  (get-href pdb (get-in report [:logs :href]))]

        (is (= #{{:name "total", :value 1, :category "events"}
                 {:name "changed", :value 1, :category "resources"}
                 {:name "total", :value 1, :category "changes"}
                 {:name "total", :value 8, :category "resources"}}
               (set (filter (every-pred (comp #{"total" "changed"} :name)
                                        (comp #{"events" "changes" "resources"} :category))
                            metrics))))

        (is (some (fn [e]
                    (and (= 1 (:line e))
                         (= #{"notice" "notify" "hi" "class"}
                            (set (:tags e)))))
                  logs))

        (is (= 3 (count
                  (filter #(= "notice" (:level %))
                          logs))))

        (is (= 6 (count
                  (filter #(= "info" (:level %))
                          logs))))))))

(defn read-gc-count-metric []
  ;; metrics are global, so...
  (counters/value (:report-purges pdb-services/admin-metrics)))

(deftest ^:integration report-ttl
  (with-open [pg (int/setup-postgres)]
    (with-open [pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {})]
      (testing "Run agent once to populate database"
        (int/run-puppet-as "ttl-agent" ps pdb "notify { 'irrelevant manifest': }"))

      (testing "Verify we have a report"
        (is (= 1 (count (int/pql-query pdb "reports { certname = 'ttl-agent' }"))))))

    (testing "Sleep for one second to make sure we have a ttl to exceed"
      (Thread/sleep 1000))

    (let [initial-gc-count (counters/value (:report-purges pdb-services/admin-metrics))]
      (with-open [pdb (int/run-puppetdb pg {:database {:report-ttl "1s"}})]
        (let [start-time (System/currentTimeMillis)]
          (loop []
            (cond
              (> (- (System/currentTimeMillis) start-time) tu/default-timeout-ms)
              (throw (ex-info "Timeout waiting for pdb gc to happen" {}))

              (> (read-gc-count-metric) initial-gc-count)
              true ;; gc happened

              :default
              (do
                (Thread/sleep 250)
                (recur)))))

        (testing "Verify that the report has been deleted"
          (is (= 0 (count (int/pql-query pdb "reports { certname = 'ttl-agent' }")))))))))
