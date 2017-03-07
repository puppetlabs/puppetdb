(ns puppetlabs.puppetdb.integration.storeconfigs.puppetdb-query
  (:require [clojure.test :refer :all]
            [me.raynes.fs :as fs]
            [puppetlabs.puppetdb.integration.fixtures :as int]))

(deftest ^:integration puppetdb-query-function
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (testing "Initial agent run, to populate puppetdb with data to query"
      (int/run-puppet ps pdb "notify { 'hello, world!': }"))

    (let [test-out-file (fs/temp-file "test_puppetdb_query" ".txt")]
      (testing "Agent run with puppedb_query in the manifest"

        (int/run-puppet ps pdb
                        (str "node default {"
                             "  $counts = puppetdb_query(['from', 'catalogs',"
                             "                            ['extract', [['function', 'count']]]])"
                             "  $count = $counts[0]['count']"
                             "  file { '" (.getCanonicalPath test-out-file) "':"
                             "        ensure  => present,"
                             "        content => \"${count}\""
                             "  }"
                             "}"))

        (testing "should write the correct catalog count to a temp file"
          (is (= "1" (slurp test-out-file))))))))
