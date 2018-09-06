(ns puppetlabs.puppetdb.integration.smoke
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]))

(deftest ^:integration simple-agent-run
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Agent run succeeds"
      (let [{:keys [out]} (int/run-puppet-as "my-agent" ps pdb "notify { 'hello, world!': }")]
        (is (re-find #"hello, world" out))))

    (testing "Agent run data can be queried"
      (are [query result] (= result (int/pql-query pdb query))
        "nodes[certname] {}" [{:certname "my-agent"}]
        "catalogs[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
        "factsets[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
        "reports[certname, environment] {}" [{:certname "my-agent", :environment "production"}]))

    (testing "transaction-uuid"
      (let [catalog-uuid (first (int/pql-query pdb "catalogs [transaction_uuid] {}"))
            report-uuid (first (int/pql-query pdb "reports [transaction_uuid] {}"))]
        (testing "is available from puppetdb"
          (is (not (nil? catalog-uuid)))
          (is (not (nil? report-uuid))))
        (testing "is equal on the catalog and report"
          (is (= catalog-uuid report-uuid)))))))


(deftest ^:integration rich-data-agent-run
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {:agent {:rich-data true}})]

    (testing "Agent run succeeds"
      (let [{:keys [out]} (int/run-puppet-as "my-agent" ps pdb "notify { 'foo':\n  message => {\n  'regex'     => /[a-z]+/,\n  'object'    => Object[{name => 'FindMe', 'attributes' => {'x' => String}}],\n  'version'   => SemVer('3.4.1'),\n  'semver'    => SemVerRange('>=3.4.1'),\n  'timestamp' => Timestamp('1994-03-25T19:30:00'),\n  'timespan'  => Timespan('3-10:00:00'),\n  }\n}" {:rich-data true})]
        (is (re-find #">=3.4.1" out))))

    (testing "Agent run data can be queried"
      (are [query result] (= result (int/pql-query pdb query))
        "nodes[certname] {}" [{:certname "my-agent"}]
        "catalogs[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
        "factsets[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
        "reports[certname, environment] {}" [{:certname "my-agent", :environment "production"}]))

    (testing "transaction-uuid"
      (let [catalog-uuid (first (int/pql-query pdb "catalogs [transaction_uuid] {}"))
            report-uuid (first (int/pql-query pdb "reports [transaction_uuid] {}"))]
        (testing "is available from puppetdb"
          (is (not (nil? catalog-uuid)))
          (is (not (nil? report-uuid))))
        (testing "is equal on the catalog and report"
          (is (= catalog-uuid report-uuid)))))))



