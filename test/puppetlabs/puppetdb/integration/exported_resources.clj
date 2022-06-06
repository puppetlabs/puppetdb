(ns puppetlabs.puppetdb.integration.exported-resources
  (:require
   [clojure.test :refer :all]
   [puppetlabs.puppetdb.integration.fixtures :as int]
   [puppetlabs.puppetdb.testutils :refer [with-caught-ex-info]]))

(deftest ^:integration basic-collection
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {})]
    (let [agents ["agent-1" "agent-2"]
          manifest-for (fn manifest-for [certname]
                         (str "@@notify { 'Hello from " certname "': }\n"
                              "Notify <<| |>>"))]
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
        (let [ex (with-caught-ex-info
                   (int/run-puppet-as "collector" ps pdb
                                      "Notify <<| title == 'DUPE NOTIFY' |>>"))
              data (ex-data ex)]
          (is (= ::int/bundle-exec-failure (:kind data)))
          (is (re-find #"duplicate resource was found while collecting exported resources"
                       (get-in data [:result :err]))))))))

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
    (let [test-collection (fn test-collection [manifest positive-regexes negative-regexes]
                            (let [{:keys [out]} (int/run-puppet-as "collector" ps pdb manifest)]
                              (doseq [r positive-regexes]
                                (is (re-find r out)))
                              (doseq [r negative-regexes]
                                (is (not (re-find r out))))))]

      (testing "Run puppet to create resources for collection"
        (int/run-puppet-as "exporter" ps pdb
                           (str "@@notify { 'message-a': name => 'a', tag => 'here' }"
                                "@@notify { 'message-b': name => 'b', tag => 'there' }"
                                "@@notify { 'message-c': name => 'c', tag => ['here', 'there'] }"
                                "@@notify { 'message-d': name => 'd' }")))

      (testing "= query"
        (test-collection "Notify <<| name == 'a' |>>"
                         [#"message-a"]
                         [#"message-b" #"message-c" #"message-d"]))

      (testing "!= query"
        (test-collection "Notify <<| name != 'a' |>>"
                         [#"message-b" #"message-c" #"message-d"]
                         [#"message-a"]))

      (testing "'or' query"
        (test-collection "Notify <<| name == 'a' or name == 'b' |>>"
                         [#"message-a" #"message-b"]
                         [#"message-c" #"message-d"]))

      (testing "'and' query"
        (test-collection "Notify <<| title == 'message-a' and name == 'a' |>> "
                         [#"message-a"]
                         [#"message-b" #"message-c" #"message-d"]))

      (testing "nested query"
        (test-collection (str "Notify <<| (title == 'message-a' or title == 'message-b') and "
                              "           (name == 'a' or name == 'b') |>>")
                         [#"message-a" #"message-b"]
                         [#"message-c" #"message-d"]))

      (testing "title query"
        (test-collection "Notify <<| title == 'message-a' |>>"
                         [#"message-a"]
                         [#"message-b" #"message-c" #"message-d"]))

      (testing "title query when nothing matches"
        (test-collection "Notify <<| title == 'message-q' |>>"
                         []
                         [#"message-a" #"message-b" #"message-c" #"message-d"]))

      (testing "title query with uri-invalid characters"
        (test-collection "Notify <<| title != 'a string with spaces and & and ? in it' |>>"
                         [#"message-a" #"message-b" #"message-c" #"message-d"]
                         []))

      (testing "tag query"
        (test-collection "Notify <<| tag == 'here'|>> "
                         [#"message-a" #"message-c"]
                         [#"message-b" #"message-d"]))

      (testing "inverse tag query"
        (test-collection "Notify <<| tag != 'here'|>> "
                         [#"message-b" #"message-d"]
                         [#"message-a" #"message-c"]))

      (testing "tag queries should be case-insensitive"
        (test-collection "Notify <<| tag == 'HERE'|>> "
                         [#"message-a" #"message-c"]
                         [#"message-b" #"message-d"]))

      (testing "puppetdb query function"
        (test-collection (str "$titles = puppetdb_query(['from', 'resources',"
                              "                          ['extract', ['title'],"
                              "                           ['and', ['=', 'type', 'Notify'],"
                              "                                   ['=', ['parameter', 'name'], 'a']]]])"
                              "notify { \"titles is ${titles}\": }")
                         [#"message-a"]
                         [#"message-b" #"message-c" #"message-d"])))))
