(ns puppetlabs.puppetdb.integration.smoke
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :refer [test-rich-data?]]
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

(deftest ^:integration terminus-can-omit-catalog-edges
  (with-open [pg (int/setup-postgres)
              pdb (int/run-puppetdb pg {})
              ps (int/run-puppet-server [pdb] {:terminus {:main {:include_catalog_edges false}}})]
    (testing "Agent run succeeds"
      (let [{:keys [out]} (int/run-puppet-as "my-agent" ps pdb "notify { 'test1': }\n notify { 'test2': before => Notify[test1] }")]
        (is (re-find #"test1" out))))

    (testing "Agent run data can be queried"
      (are [query result] (= result (int/pql-query pdb query))
        "nodes[certname] {}" [{:certname "my-agent"}]
        "factsets[certname, environment] {}" [{:certname "my-agent" :environment "production"}]
        "catalogs[certname, environment] {}" [{:certname "my-agent" :environment "production"}]
        "reports[certname, environment] {}" [{:certname "my-agent" :environment "production"}]))

    (testing "catalog does not contain edges"
      (is (= [{:certname "my-agent" :edges {:data nil
                                            :href "/pdb/query/v4/catalogs/my-agent/edges"}}]
             (int/pql-query pdb "catalogs[certname, edges] {}"))))))

(when test-rich-data?
  (def rich-data-tests
    [
     ; PuppetDB always uses keyword keys in Maps
     {:name "literal_undef" :code "{ 'wrapper' => [undef] }" :expected  {:wrapper [nil]}}
     {:name "literal_false" :code "{ 'wrapper' => false }" :expected {:wrapper false}}

     {:name "literal_default" :code "default" :expected "default"}
     {:name "literal_integer" :code "47" :expected 47}
     {:name "literal_float" :code "3.14" :expected 3.14}
     {:name "literal_true" :code "true" :expected true}
     {:name "literal_string" :code "\"hello\"" :expected "hello"}
     {:name "string_with_single_quote" :code "\"ta'phoenix\"" :expected "ta'phoenix"}
     {:name "string_with_double_quote" :code "\"he said \\\"hi\\\"\"" :expected "he said \"hi\""}
     {:name "regexp" :code "/[a-z]+/" :expected "/[a-z]+/"}
     {:name "deferred" :code "Deferred('join', [[1, 2, 3], ':'])" :expected "Deferred({'name' => 'join', 'arguments' => [[1, 2, 3], ':']})"}
     {:name "sensitive_deferred" :code "Sensitive(Deferred('join', [[1, 2, 3], ':']))" :expected nil}
     {:name "sensitive" :code "Sensitive('password')" :expected nil}
     {:name "timestamp" :code "Timestamp('2012-10-10')" :expected "2012-10-10T00:00:00.000000000 UTC"}
     {:name "hash_and_array" :code "{'a' => [1, 2, 3], 'b' => 'hello'}" :expected {:a [1, 2, 3] :b "hello"} }
     {:name "special_key" :code "{ ['special', 'key'] => 10 }" :expected {(keyword "[\"special\", \"key\"]") 10}}
     {:name "hash_with_sensitive_key" :code "{Sensitive(hush) => 42 }"
      :valid? (fn [test-data]
                (let [test-keys (keys test-data)]
                  (and (= (count test-keys) 1)
                       (re-matches #"#<Sensitive \[value redacted\]:[0-9]+>" (name (first test-keys)))
                       (= 42 ((first test-keys) test-data)))))}
     {:name "hash_with_sensitive_val" :code "{ x => Sensitive(hush) }"
      :valid? (fn [test-data]
                (and (= (count (keys test-data)) 1)
                     (re-matches #"#<Sensitive \[value redacted\]:[0-9]+>" (:x test-data))))}
     {:name "hash_ptype_key" :code "{'__ptype' => 10}" :expected {(keyword "reserved key: __ptype") 10}}
     {:name "binary_value" :code "Binary(\"hello\", \"%s\")" :expected "aGVsbG8="}
     {:name "a_type" :code "Integer[0,100]" :expected "Integer[0, 100]"}

     ; Car is defined in the manifest generated in the test below
     ; {:name "user_defined_type" :code "Car" :expected "Car"}
     ; {:name "user_defined_object" :code "Car(abc123)" :expected "Car({'regnbr' => 'abc123'})"}
     ])

  (deftest ^:integration rich-data-agent-run
    (with-open [pg (int/setup-postgres)
                pdb (int/run-puppetdb pg {})
                ps (int/run-puppet-server [pdb] {:agent {:rich-data true}})]

      (testing "Agent run succeeds"
        (let [manifest (str "type Car = Object[attributes => {regnbr => String}];\n"
                            (apply str (for [{:keys [name code]} rich-data-tests]
                                         (str "notify { '" name "':"
                                              "  message => " code
                                              "}"))))
              {:keys [out]} (int/run-puppet-as "my-agent" ps pdb manifest {:rich-data true})]
          (is (re-find #"a-z" out))))

      (testing "Agent run data can be queried"
        (are [query result] (= result (int/pql-query pdb query))
          "nodes[certname] {}" [{:certname "my-agent"}]
          "catalogs[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
          "factsets[certname, environment] {}" [{:certname "my-agent", :environment "production"}]
          "reports[certname, environment] {}" [{:certname "my-agent", :environment "production"}]))

      (testing "Catalog resource parameters stored in proper format"
        (let [[{{all-resources :data} :resources} :as result]
                (int/pql-query pdb "catalogs[certname, resources] { resources { type = 'Notify' and title = 'regexp' } }")
              resources (remove #(or (= "Stage" (:type %))
                                     (and (= "Class" (:type %))
                                          (or (= "main" (:title %))
                                              (= "Settings" (:title %)))))
                                all-resources)]
          (is (= 1 (count result)))
          (is (= "my-agent" (:certname (first result))))
          (is (= (count rich-data-tests) (count resources)))

          (doseq [resource resources]
            (let [stored-value (:message (:parameters resource))
                  filter-fn (fn [data] (= (:title resource) (:name data)))
                  [test-resource :as resources] (filter filter-fn rich-data-tests)]
              (is (= 1 (count resources)))

              (if (:valid? test-resource)
                (is ((:valid? test-resource) stored-value))
                (is (= (:expected test-resource) stored-value)))))))

      (testing "transaction-uuid"
        (let [catalog-uuid (first (int/pql-query pdb "catalogs [transaction_uuid] {}"))
              report-uuid (first (int/pql-query pdb "reports [transaction_uuid] {}"))]
          (testing "is available from puppetdb"
            (is (not (nil? catalog-uuid)))
            (is (not (nil? report-uuid))))
          (testing "is equal on the catalog and report"
            (is (= catalog-uuid report-uuid))))))))
