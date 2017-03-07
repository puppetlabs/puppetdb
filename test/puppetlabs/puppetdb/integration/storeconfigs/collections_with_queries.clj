(ns puppetlabs.puppetdb.integration.storeconfigs.collections-with-queries
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [me.raynes.fs :as fs]))

(deftest ^:integration collections-with-queries
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [temp-dir (fs/temp-dir "collections-with-queries")
          test-collection (fn test-collection [manifest files-to-check]
                            (fs/delete-dir temp-dir)
                            (fs/mkdir temp-dir)
                            (int/run-puppet-as "collector" ps pdb manifest)
                            (doseq [f files-to-check]
                              (is (fs/exists? (str temp-dir "/" f)))))]

      (testing "Run puppet to create resources for collection"
        (int/run-puppet-as "exporter" ps pdb
                           (str "@@file { '" temp-dir "/file-a':"
                                "  ensure  => present,"
                                "  mode    => '0777',"
                                "  content => 'foo'"
                                "}"
                                "@@file { '" temp-dir "/file-b':"
                                "  ensure  => present,"
                                "  mode    => '0755',"
                                "  content => 'bar'"
                                "}"
                                "@@file { '" temp-dir "/file-c':"
                                "  ensure  => present,"
                                "  mode    => '0744',"
                                "  content => 'foo',"
                                "}"
                                "@@file { '" temp-dir "/file-d':"
                                "  ensure  => present,"
                                "  mode    => '0744',"
                                "  content => 'bar'"
                                "}")))

      (testing "= query"
        (test-collection "File <<| mode == '0744' |>>"
                         ["file-c" "file-d"]))

      (testing "!= query"
        (test-collection "File <<| mode != '0755' |>>"
                         ["file-a" "file-c" "file-d"]))

      (testing "'or' query"
        (test-collection "File <<| mode == '0755' or content == 'bar' |>>"
                         ["file-b" "file-d"]))

      (testing "'and' query"
        (test-collection "File <<| mode == '0744' and content == 'foo' |>> "
                         ["file-c"]))

      (testing "nested query"
        (test-collection "File <<| (mode == '0777' or mode == '0755') and content == 'bar' |>>"
                         ["file-b"])))))
