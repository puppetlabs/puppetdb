(ns puppetlabs.puppetdb.integration.db-garbage-collection.report-ttl
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.puppetdb.cheshire :as json]))

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

    (with-open [pdb (int/run-puppetdb pg {:database {:report-ttl "1s"}})
                ps (int/run-puppet-server [pdb] {})]

      ;; TODO: this is really fragile, it would be better if there were some way
      ;; to tell that the GC had finished.  We could maybe scrape the logs if this
      ;; proves too fragile.
      (testing "sleep 5 seconds to allow GC to complete"
        (Thread/sleep 5000))

      (testing "Verify that the report has been deleted"
        (is (= 0 (count (int/pql-query pdb "reports { certname = 'ttl-agent' }"))))))))

