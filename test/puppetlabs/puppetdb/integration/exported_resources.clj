(ns puppetlabs.puppetdb.integration.exported-resources
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.integration.fixtures :as int]
            [me.raynes.fs :as fs]))

(defn manifest-for [certname]
  (str "@@notify { 'Hello from " certname "': }\n"
       "Notify <<| |>>"))

(deftest ^:integration basic-collection
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [agents ["agent-1" "agent-2"]]
      (testing "Run with an exported resource first"
        (doseq [agent agents]
          (int/run-puppet-as agent ps pdb (manifest-for agent))))

      (testing "Then run again, and check each agent sees all the others"
        (doseq [agent agents]
          (let [{:keys [out]} (int/run-puppet-as agent ps pdb (manifest-for agent))]
            (doseq [expected-agent agents]
              (is (re-find (re-pattern (str "Notice: Hello from " expected-agent))
                           out))))))

      (testing "attempt export with undef array elements"
        ;; just see that it doesn't throw
        (int/run-puppet-as "agent-1" ps pdb
                           "@@notify { 'test': tag => [undef, 'a', 'b'] }"))

      (testing "Run puppet on exporters to create duplicate exported resources"
        (doseq [agent agents]
          (int/run-puppet-as agent ps pdb "@@notify { 'DUPE NOTIFY': }")))

      (testing "Run puppet on collector and expect failure"
        (is (thrown+? (and (= (:kind %) ::int/bundle-exec-failure)
                           (re-find #"duplicate resource was found while collecting exported resources"
                                    (get-in % [:result :err])))
                      (int/run-puppet-as "collector" ps pdb "Notify <<| title == 'DUPE NOTIFY' |>>")))))))



(deftest ^:integration deactivated-nodes
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [exporter-manifest  "@@notify { 'Hello from exporter': }"
          test-collection (fn test-collection [should-be-active?]
                            (let [{agent-stdout :out} (int/run-puppet-as "collector" ps pdb "Notify <<| |>>")
                                  {status-stdout :out} (int/run-puppet-node-status pdb "exporter") ]
                              (if should-be-active?
                                (do
                                  (is (re-find #"Hello from exporter" agent-stdout))
                                  (is (re-find #"Currently active" status-stdout)))
                                (do
                                  (is (not (re-find #"Hello from exporter" agent-stdout)))
                                  (is (re-find #"Deactivated at" status-stdout))))))]

      (testing "Resources should be collected before deactivation"
        (int/run-puppet-as "exporter" ps pdb exporter-manifest)
        (test-collection true))

      (testing "Resources from deactivated nodes should be ignored"
        (int/run-puppet-node-deactivate pdb "exporter")
        (test-collection false))

      (testing "Resources from reactivated nodes should be collected"
        (int/run-puppet-as "exporter" ps pdb exporter-manifest)
        (test-collection true)))))

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
