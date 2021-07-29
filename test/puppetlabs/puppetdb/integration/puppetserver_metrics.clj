(ns puppetlabs.puppetdb.integration.puppetserver-metrics
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(deftest ^:integration puppetserver-http-client-metrics
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Run puppet using exported resources and puppetdb_query function"
      (int/run-puppet-as "exporter" ps pdb
                         (str
                          "$counts = puppetdb_query(['from', 'catalogs',"
                          "                            ['extract', [['function', 'count']]]])"
                          "@@notify { 'hello world': }"))

      ;; Collecting resources triggers a `facts find` and `resource search`
      (int/run-puppet-as "collector" ps pdb "Notify <<| |>>"))

    (testing "Puppet Server status endpoint contains expected puppetdb metrics"

      (let [status-endpoint (str "https://localhost:" (-> ps int/server-info :port) "/status/v1")
            all-svcs-status (svc-utils/get-ssl (str status-endpoint "/services"))]
        (is (= 200 (:status all-svcs-status)))
        ;; in older versions of Puppet Server (pre-5.0), the `master` status
        ;; didn't exist. Since these tests are run against multiple versions
        ;; of Puppet Server, this ensures that we're only testing that the
        ;; `master` status has appropriate content on the right versions.
        (when (some? (get-in all-svcs-status [:body :master]))
          (let [resp (svc-utils/get-ssl (str status-endpoint "/services/master?level=debug"))]
            (is (= 200 (:status resp)))

            (let [metrics (get-in resp [:body :status :experimental :http-client-metrics])]
              (is (= #{["puppetdb" "command" "replace_catalog"]
                       ["puppetdb" "command" "replace_facts"]
                       ["puppetdb" "command" "store_report"]
                       ["puppetdb" "query"]
                       ["puppetdb" "resource" "search"]}
                     (set (map :metric-id metrics)))))))))

    (testing "PuppetDB metrics are updated for compressed commands"
      ;; the terminus is configured to send gzipped commands without a
      ;; 'Content-Length' header this test checks that the custom
      ;; 'X-Uncompressed-Length' header updates the PDB size metric
      (let [size-metrics-url (str "https://localhost:"
                                  (-> pdb int/server-info :base-url :port)
                                  "/metrics/v2/read/puppetlabs.puppetdb.mq:name=global.size")
            metrics-resp (svc-utils/get-ssl size-metrics-url)]
        (is (= 200 (:status metrics-resp)))
        ;; assert that the size metric has been updated and no values are 0.0
        (is (->> metrics-resp
                 :body
                 :value
                 vals
                 (map #(not= 0.0 %))
                 (every? true?)))))

    (testing "PuppetDB metrics are updated for submitting new facts"
      ;; the terminus is configured to send gzipped commands like
      ;; "replace-facts", this test checks that the PDB new-fact-time
      ;; metric is updated
      (let [new-fact-metrics-url (str "https://localhost:"
                                  (-> pdb int/server-info :base-url :port)
                                  "/metrics/v2/read/puppetlabs.puppetdb.storage:name=new-fact-time")
            metrics-resp (svc-utils/get-ssl new-fact-metrics-url)]
        (is (= 200 (:status metrics-resp)))
        ;; assert that the new-fact-time metric has been updated and no values are 0.0
        (is (->> metrics-resp
                 :body
                 :value
                 vals
                 (map #(not= 0.0 %))
                 (every? true?)))))))