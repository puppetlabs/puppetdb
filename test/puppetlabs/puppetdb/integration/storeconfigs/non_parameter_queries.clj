(ns puppetlabs.puppetdb.integration.storeconfigs.non-parameter-queries
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [me.raynes.fs :as fs]))

(deftest ^:integration non-parameter-queries
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [temp-dir (fs/temp-dir "non-parameter-queries")
          test-collection (fn test-collection [manifest files-to-check]
                            (fs/delete-dir temp-dir)
                            (fs/mkdir temp-dir)
                            (int/run-puppet-as "collector" ps pdb manifest)
                            (doseq [f files-to-check]
                              (is (fs/exists? (str temp-dir "/" f)))))]

      (testing "Run puppet to create resources for collection"
        (int/run-puppet-as "exporter" ps pdb
                           (str "@@file { 'file-a':"
                                "  path   => '" temp-dir "/file-a',"
                                "  ensure => present,"
                                "  tag    => 'here',"
                                "}"

                                "@@file { 'file-b':"
                                "  path   => '" temp-dir "/file-b',"
                                "  ensure => present,"
                                "  tag    => ['here', 'there'],"
                                "}"

                                "@@file { 'file-c':"
                                "  path   => '" temp-dir "/file-c',"
                                "  ensure => present,"
                                "  tag    => ['there'],"
                                "}")))

      (testing "title query"
        (test-collection "File <<| title == 'file-c' |>>"
                         ["file-c"]))

      (testing "title query when nothing matches"
        (test-collection "File <<| title == 'file' |>>"
                         []))

      (testing "title query with uri-invalid characters"
        (test-collection "File <<| title != 'a string with spaces and & and ? in it' |>>"
                         ["file-a" "file-b" "file-c"]))

      (testing "tag query"
        (test-collection "File <<| tag == 'here'|>> "
                         ["file-a" "file-b"]))

      (testing "inverse tag query"
        (test-collection "File <<| tag != 'here'|>> "
                         ["file-c"]))

      (testing "tag queries should be case-insensitive"
        (test-collection "File <<| tag == 'HERE'|>> "
                         ["file-a" "file-b"])))))

