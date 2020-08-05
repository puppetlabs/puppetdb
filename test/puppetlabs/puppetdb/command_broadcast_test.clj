(ns puppetlabs.puppetdb.command-broadcast-test
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.integration.fixtures :as int]
   [puppetlabs.puppetdb.utils :as utils]
   [puppetlabs.puppetdb.testutils.cli :refer [example-report
                                              example-facts
                                              example-catalog
                                              example-certname]]
   [puppetlabs.puppetdb.command.constants :as cmd-consts]
   [puppetlabs.puppetdb.testutils.services :as svc-utils]
   [puppetlabs.puppetdb.utils.metrics :as mutils]
   [puppetlabs.puppetdb.command :as cmd]
   [clojure.string :as str])
  (:import (java.sql SQLException)))

(def commands
  {:replace-facts {:data (assoc example-facts :values {:foo "the foo"})
                   :cname (:replace-facts cmd-consts/command-names)
                   :ver cmd-consts/latest-facts-version}
   :store-report {:data example-report
                  :cname (:store-report cmd-consts/command-names)
                  :ver cmd-consts/latest-report-version}
   :replace-catalog {:data example-catalog
                     :cname (:replace-catalog cmd-consts/command-names)
                     :ver cmd-consts/latest-catalog-version}})

(defn post-blocking-ssl-cmd [pdb cmd]
  (let [{:keys [data cname ver]} (cmd commands)
        cmd-url (-> pdb int/server-info :base-url
                    (assoc :prefix "/pdb/cmd" :version :v1))
        url-params (utils/cmd-url-params {:command cname
                                          :version ver
                                          :certname example-certname
                                          :timeout 5 ; seconds
                                          :producer-timestamp (-> data
                                                                  :producer_timestamp
                                                                  str)})
        url (str (utils/base-url->str cmd-url) url-params)]
    (svc-utils/post-ssl-or-throw url data)))

(defn populate-db
  ([pdb]
   (populate-db pdb false))
  ([pdb assert-errors?]
   (doseq [cmd (keys commands)]
     (if assert-errors?
       (is (thrown? Exception (post-blocking-ssl-cmd pdb cmd)))
       (post-blocking-ssl-cmd pdb cmd)))))

(deftest cmd-broadcast
  (with-open [pg1 (int/setup-postgres)
              pg2 (int/setup-postgres)
              pdb1 (int/run-puppetdb pg1 {:database {"gc-interval" 0}
                                          :database-pg1 (int/server-info pg1)
                                          :database-pg2 (int/server-info pg2)})]
    ;; post a few commands to trigger broadcast to both pgs
    (populate-db pdb1)

    (testing "Expected data is present in pdb1's read-database pg1"
      (let [expected [{:certname "foo.local"}]]
        (is (= expected (int/pql-query pdb1 "reports [certname] {}")))
        (is (= expected (int/pql-query pdb1 "facts [certname] {}")))
        (is (= expected (int/pql-query pdb1 "catalogs [certname] {}")))))

    (let [port (-> pdb1 int/server-info :base-url :port)
          list-metrics (fn [metric-type]
                         ;; return metrics in :name=pg1.<metric-name> format
                         (-> (str "https://localhost:" port "/metrics/v2/list")
                             svc-utils/get-ssl
                             :body
                             :value
                             metric-type
                             keys))]

      (testing "Expected number of storage metrics are created for both PGs"
        (let [storage-metrics (list-metrics :puppetlabs.puppetdb.storage)
              ;; filter out non-broadcast metrics created in the registry during other tests
              broadcast-metrics (filter #(re-find (re-pattern (str "pg1" "|" "pg2")) (str %)) storage-metrics)
              expected-count 21  ;; 21 per pg metrics registered in storage.clj
              [pg-1 pg-2] (vals (group-by #(subs (str %) 1 9) broadcast-metrics))]
          (is (= expected-count (count pg-1)))
          (is (= (count pg-1) (count pg-2)))))

      (testing "Expected number of admin metrics are created for both PGs"
        (let [admin-metrics (list-metrics :puppetlabs.puppetdb.admin)
              ;; filter out non-broadcast metrics created in the registry during other tests
              broadcast-metrics (filter #(re-find (re-pattern (str "pg1" "|" "pg2")) (str %)) admin-metrics)
              expected-count 13 ;; 13 per pg admin metrics registered in services.clj
              [pg-1 pg-2] (vals (group-by #(subs (str %) 1 9) broadcast-metrics))]
          (is (= expected-count (count pg-1)))
          (is (= (count pg-1) (count pg-2)))))

      (testing "Storage metrics updated for both PGs"
        (let [report-min-time (fn [pg]
                                (-> (str "https://localhost:"
                                         port
                                         "/metrics/v2/read/"
                                         "puppetlabs.puppetdb.storage"
                                         (str ":name=" pg ".store-report-time"))
                                    svc-utils/get-ssl
                                    :body
                                    :value
                                    :Min))]
          ;; spot check that the store report timer Min val updated for both pgs
          (is (not= 0.0 (report-min-time "pg1")))
          (is (not= 0.0 (report-min-time "pg2"))))))

    (with-open [pdb2 (int/run-puppetdb pg2 {:database {"gc-interval" 0}})]
      (testing "Expected data is present in pdb2's read-database pg2"
        (let [expected [{:certname "foo.local"}]]
          (is (= expected (int/pql-query pdb2 "reports [certname] {}")))
          (is (= expected (int/pql-query pdb2 "facts [certname] {}")))
          (is (= expected (int/pql-query pdb2 "catalogs [certname] {}"))))))))

(deftest cmd-broadcast-with-one-pg-down
  (with-open [pg1 (int/setup-postgres)
              pg2 (int/setup-postgres)
              pdb1 (int/run-puppetdb pg1 {:database {"gc-interval" 0}
                                          :database-pg1 (int/server-info pg1)
                                          :database-pg2 (int/server-info pg2)})]
    (let [shared-globals (-> pdb1
                             int/server-info
                             :app
                             .app_context
                             deref
                             :service-contexts
                             :PuppetDBServer
                             :shared-globals)
          db-names (:scf-write-db-names shared-globals)
          dbs (:scf-write-dbs shared-globals)
          db2 (nth dbs (.indexOf db-names "pg2"))
          dlo (:path (:dlo shared-globals))
          orig-exec-cmds cmd/exec-command]

      ;; break exec-command for all db2 submissions
      (with-redefs [cmd/exec-command (fn [{:keys [command version] :as cmd} db conn-status start]
                                       (when (= db2 db)
                                         (throw (SQLException. "BOOM")))
                                       (orig-exec-cmds cmd db conn-status start))]

        ;; post a few commands to trigger broadcast, submission will fail to one backend
        (populate-db pdb1)

        (testing "Expected data is present in pdb1's read-database pg1"
          (let [expected [{:certname "foo.local"}]]
            (is (= expected (int/pql-query pdb1 "reports [certname] {}")))
            (is (= expected (int/pql-query pdb1 "facts [certname] {}")))
            (is (= expected (int/pql-query pdb1 "catalogs [certname] {}")))))

        (testing "dlo should be clean because command was succesfully delivered to one pg"
          ;; file-seq returns an entry for the dlo dir
          (is (= 1 (-> dlo str clojure.java.io/file file-seq count))))))))

(deftest cmd-broadcast-with-all-pgs-down
  (with-open [pg1 (int/setup-postgres)
              pg2 (int/setup-postgres)
              pdb1 (int/run-puppetdb pg1 {:database {"gc-interval" 0}
                                          :database-pg1 (int/server-info pg1)
                                          :database-pg2 (int/server-info pg2)})]
    (let [shared-globals (-> pdb1
                             int/server-info
                             :app
                             .app_context
                             deref
                             :service-contexts
                             :PuppetDBServer
                             :shared-globals)
          dlo (:path (:dlo shared-globals))
          ;; the dlo file-seq includes one entry for the dir and two per failed command
          expected-dlo-count (inc (* 2 (count (keys commands))))]

      ;; break exec-command for all submissions to all pgs
      (with-redefs [cmd/exec-command (fn [& args] (throw (SQLException. "BOOM")))
                    ;; don't allow retries to speed cmd ending up in dlo
                    cmd/maximum-allowable-retries 0]

        ;; post a few commands all of which should fail. Assertion done in populate-db
        (populate-db pdb1 true)

        ;; account for race between cmds being discared to dlo vs. checking for the discards
        (let [dlo-files (loop [retries 0]
                          (let [dlo-files (-> dlo str clojure.java.io/file file-seq)]
                            (cond
                              (= expected-dlo-count (count dlo-files)) dlo-files
                              (> retries 10) (throw (ex-info "dlo in unexpected state"
                                                             {:dlo-files dlo-files}))
                              :else
                              (do
                                (Thread/sleep 100)
                                (recur (inc retries))))))]

          (testing "dlo should contain two entries per failed command"
            (is (= expected-dlo-count (count dlo-files)))))))))
