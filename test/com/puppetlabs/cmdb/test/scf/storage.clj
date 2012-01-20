(ns com.puppetlabs.cmdb.test.scf.storage
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.cmdb.test.catalog :as testcat]
            [clojure.java.jdbc :as sql])
  (:use [com.puppetlabs.cmdb.scf.storage]
        [clojure.test]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.testutils :only [test-db]]))

(def *db* (test-db))

(deftest hash-computation
  (testing "Hashes for resources"

    (testing "should error on bad input"
      (is (thrown? AssertionError (resource-identity-hash nil)))
      (is (thrown? AssertionError (resource-identity-hash []))))

    (testing "should be equal for the base case"
      (is (= (resource-identity-hash {})
             (resource-identity-hash {}))))

    (testing "shouldn't change for identical input"
      (doseq [i (range 10)
              :let [r (testcat/random-kw-resource)]]
        (is (= (resource-identity-hash r)
               (resource-identity-hash r)))))

    (testing "shouldn't change for equivalent input"
      (is (= (resource-identity-hash {:foo 1 :bar 2})
             (resource-identity-hash {:bar 2 :foo 1})))
      (is (= (resource-identity-hash {:tags #{1 2 3}})
             (resource-identity-hash {:tags #{3 2 1}}))))

    (testing "should be different for non-equivalent resources"
      ; Take a population of 5 resource, put them into a set to make
      ; sure we only care about a population of unique resources, take
      ; any 2 elements from that set, and those 2 resources should
      ; have different hashes.
      (let [candidates (into #{} (repeatedly 5 testcat/random-kw-resource))
            pairs      (clojure.contrib.combinatorics/combinations candidates 2)]
        (doseq [[r1 r2] pairs]
          (is (not= (resource-identity-hash r1)
                    (resource-identity-hash r2))))))))

(deftest catalog-persistence
  (testing "Persisted catalogs"
    (let [catalog {:certname "myhost.mydomain.com"
                   :cmdb-version cat/CMDB-VERSION
                   :api-version 1
                   :version "123456789"
                   :tags #{"class" "foobar"}
                   :classes #{"foobar"}
                   :edges #{{:source {:type "Class" :title "foobar"}
                             :target {:type "File" :title "/etc/foobar"}
                             :relationship :contains}
                            {:source {:type "Class" :title "foobar"}
                             :target {:type "File" :title "/etc/foobar/baz"}
                             :relationship :contains}
                            {:source {:type "File" :title "/etc/foobar"}
                             :target {:type "File" :title "/etc/foobar/baz"}
                             :relationship :required-by}}
                   :resources {{:type "Class" :title "foobar"} {:type "Class" :title "foobar" :exported false}
                               {:type "File" :title "/etc/foobar"} {:type       "File"
                                                                    :title      "/etc/foobar"
                                                                    :exported   false
                                                                    :file       "/tmp/foo"
                                                                    :line       10
                                                                    :tags       #{"file" "class" "foobar"}
                                                                    :parameters {"ensure" "directory"
                                                                                 "group"  "root"
                                                                                 "user"   "root"}}
                               {:type "File" :title "/etc/foobar/baz"} {:type       "File"
                                                                        :title      "/etc/foobar/baz"
                                                                        :exported   false
                                                                        :file       "/tmp/bar"
                                                                        :line       20
                                                                        :tags       #{"file" "class" "foobar"}
                                                                        :parameters {"ensure"  "directory"
                                                                                     "group"   "root"
                                                                                     "user"    "root"
                                                                                     "require" "File[/etc/foobar]"}}}}]

      (sql/with-connection *db*
        (initialize-store)
        (add-certname! "myhost.mydomain.com")
        (let [hash (add-catalog! catalog)]
          (associate-catalog-with-certname! hash "myhost.mydomain.com"))

        (testing "should contain proper catalog metadata"
          (is (= (query-to-vec ["SELECT cr.certname, c.api_version, c.catalog_version FROM catalogs c, certname_catalogs cr WHERE cr.catalog=c.hash"])
                 [{:certname "myhost.mydomain.com" :api_version 1 :catalog_version "123456789"}])))

        (testing "should contain a complete tags list"
          (is (= (query-to-vec ["SELECT name FROM tags ORDER BY name"])
                 [{:name "class"} {:name "foobar"}])))

        (testing "should contain a complete classes list"
          (is (= (query-to-vec ["SELECT name FROM classes ORDER BY name"])
                 [{:name "foobar"}])))

        (testing "should contain a complete edges list"
          (is (= (query-to-vec [(str "SELECT r1.type as stype, r1.title as stitle, r2.type as ttype, r2.title as ttitle, e.type as etype "
                                     "FROM edges e, resources r1, resources r2 "
                                     "WHERE e.source=r1.hash AND e.target=r2.hash "
                                     "ORDER BY r1.type, r1.title, r2.type, r2.title, e.type")])
                 [{:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar" :etype "contains"}
                  {:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "contains"}
                  {:stype "File" :stitle "/etc/foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "required-by"}])))

        (testing "should contain a complete resources list"
          (is (= (query-to-vec ["SELECT type, title, exported, sourcefile, sourceline FROM resources ORDER BY type, title"])
                 [{:type "Class" :title "foobar" :exported false :sourcefile nil :sourceline nil}
                  {:type "File" :title "/etc/foobar" :exported false :sourcefile "/tmp/foo" :sourceline 10}
                  {:type "File" :title "/etc/foobar/baz" :exported false :sourcefile "/tmp/bar" :sourceline 20}]))

          (testing "properly associated with the host"
            (is (= (query-to-vec ["SELECT cc.certname, r.type, r.title, r.exported FROM resources r, catalog_resources cr, certname_catalogs cc WHERE cr.resource=r.hash AND cc.catalog=cr.catalog ORDER BY r.type, r.title"])
                   [{:certname "myhost.mydomain.com" :type "Class" :title "foobar" :exported false}
                    {:certname "myhost.mydomain.com" :type "File" :title "/etc/foobar" :exported false}
                    {:certname "myhost.mydomain.com" :type "File" :title "/etc/foobar/baz" :exported false}])))

          (testing "with all parameters"
            (is (= (query-to-vec ["SELECT r.type, r.title, rp.name, rp.value FROM resources r, resource_params rp WHERE rp.resource=r.hash ORDER BY r.type, r.title, rp.name"])
                   [{:type "File" :title "/etc/foobar" :name "ensure" :value ["directory"]}
                    {:type "File" :title "/etc/foobar" :name "group" :value ["root"]}
                    {:type "File" :title "/etc/foobar" :name "user" :value ["root"]}
                    {:type "File" :title "/etc/foobar/baz" :name "ensure" :value ["directory"]}
                    {:type "File" :title "/etc/foobar/baz" :name "group" :value ["root"]}
                    {:type "File" :title "/etc/foobar/baz" :name "require" :value ["File[/etc/foobar]"]}
                    {:type "File" :title "/etc/foobar/baz" :name "user" :value ["root"]}])))

          (testing "with all tags"
            (is (= (query-to-vec ["SELECT r.type, r.title, t.name FROM resources r, resource_tags t WHERE t.resource=r.hash ORDER BY r.type, r.title, t.name"])
                   [{:type "File" :title "/etc/foobar" :name "class"}
                    {:type "File" :title "/etc/foobar" :name "file"}
                    {:type "File" :title "/etc/foobar" :name "foobar"}
                    {:type "File" :title "/etc/foobar/baz" :name "class"}
                    {:type "File" :title "/etc/foobar/baz" :name "file"}
                    {:type "File" :title "/etc/foobar/baz" :name "foobar"}])))))

      (testing "should noop if replaced by themselves"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash (add-catalog! catalog)]
            (replace-catalog! catalog)

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should share structure when duplicate catalogs are detected for the same host"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash (add-catalog! catalog)]
            (replace-catalog! catalog)
            ;; Store a second catalog, with the same content save the version
            (replace-catalog! (assoc catalog :version "abc123"))

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT certname FROM certname_catalogs"])
                   [{:certname "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should not share structure when storing catalogs with swapped edge targets"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (add-catalog! catalog)
          ;; Store a second catalog, with different edges (swap 2 targets)
          (add-catalog! (assoc catalog :edges #{{:source {:type "Class" :title "foobar"}
                                                 :target {:type "File" :title "/etc/foobar"}
                                                 :relationship :contains}
                                                {:source {:type "Class" :title "foobar"}
                                                 :target {:type "File" :title "/etc/foobar/baz"}
                                                 :relationship :contains}
                                                {:source {:type "File" :title "/etc/foobar"}
                                                 :target {:type "Class" :title "foobar"}
                                                 :relationship :required-by}}))

            (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalogs"])
                   [{:c 2}]))))

      (testing "should noop if replaced by themselves after using manual deletion"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (add-catalog! catalog)
          (delete-catalog! "myhost.mydomain.com")
          (add-catalog! catalog)

          (is (= (query-to-vec ["SELECT name FROM certnames"])
                 [{:name "myhost.mydomain.com"}]))))

      (testing "should be removed when deleted"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash (add-catalog! catalog)]
            (delete-catalog! hash))

          (is (= (query-to-vec ["SELECT * FROM tags"])
                 []))

          (is (= (query-to-vec ["SELECT * FROM classes"])
                 []))

          (is (= (query-to-vec ["SELECT * FROM edges"])
                 []))

          (is (= (query-to-vec ["SELECT * FROM catalog_resources"])
                 []))))

      (testing "when deleted, should leave resources alone"
        (sql/with-connection *db*
          (initialize-store)
          (let [hash (add-catalog! catalog)]
            (delete-catalog! hash))

          (is (= (query-to-vec ["SELECT * FROM resources r, catalog_resources cr WHERE r.hash=cr.resource"])
                 []))))

      (testing "when deleted, should leave certnames alone"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (add-catalog! catalog)
          (delete-catalog! "myhost.mydomain.com")

          (is (= (query-to-vec ["SELECT * FROM certnames"])
                 [{:name "myhost.mydomain.com"}]))))

      (testing "when deleted, should leave other hosts' resources alone"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (add-certname! "myhost2.mydomain.com")
          (let [hash1 (add-catalog! catalog)
                ;; Store the same catalog for a different host
                hash2 (add-catalog! (assoc catalog :certname "myhost2.mydomain.com"))]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (associate-catalog-with-certname! hash2 "myhost2.mydomain.com")
            (delete-catalog! hash1))

          ;; myhost should still be present in the database
          (is (= (query-to-vec ["SELECT name FROM certnames ORDER BY name"])
                 [{:name "myhost.mydomain.com"} {:name "myhost2.mydomain.com"}]))

          ;; myhost1 should not have any catalogs associated with it
          ;; anymore
          (is (= (query-to-vec ["SELECT certname FROM certname_catalogs ORDER BY certname"])
                 [{:certname "myhost2.mydomain.com"}]))

          ;; no tags for myhost
          (is (= (query-to-vec [(str "SELECT t.name FROM tags t, certname_catalogs cc "
                                     "WHERE t.catalog=cc.catalog AND cc.certname=?")
                                     "myhost.mydomain.com"])
                 []))

          ;; no classes for myhost
          (is (= (query-to-vec [(str "SELECT c.name FROM classes c, certname_catalogs cc "
                                     "WHERE c.catalog=cc.catalog AND cc.certname=?")
                                     "myhost.mydomain.com"])
                 []))

          ;; no edges for myhost
          (is (= (query-to-vec [(str "SELECT COUNT(*) as c FROM edges e, certname_catalogs cc "
                                     "WHERE e.catalog=cc.catalog AND cc.certname=?")
                                     "myhost.mydomain.com"])
                 [{:c 0}]))

          ;; All the resources should still be there
          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resources"])
                 [{:c 3}]))))

      (testing "when deleted without GC, should leave resources behind"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (delete-catalog! hash1))

          ;; All the resources are still present, despite not being
          ;; associated with a catalog
          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resources"])
                 [{:c 3}]))))

      (testing "when deleted and GC'ed, should leave no dangling resources"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (delete-catalog! hash1))
          (garbage-collect!)

          (is (= (query-to-vec ["SELECT * FROM resources"])
                 []))))

      (testing "when dissociated and not GC'ed, should still exist"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (dissociate-catalog-with-certname! hash1 "myhost.mydomain.com"))

          (is (= (query-to-vec ["SELECT * FROM certname_catalogs"])
                 []))

          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalogs"])
                 [{:c 1}]))))

      (testing "when dissociated and GC'ed, should no longer exist"
        (sql/with-connection *db*
          (initialize-store)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (dissociate-catalog-with-certname! hash1 "myhost.mydomain.com"))
          (garbage-collect!)

          (is (= (query-to-vec ["SELECT * FROM certname_catalogs"])
                 []))

          (is (= (query-to-vec ["SELECT * FROM catalogs"])
                 [])))))

    (testing "should noop"

      (testing "on bad input"
        (sql/with-connection *db*
          (initialize-store)
          (is (thrown? AssertionError (add-catalog! {})))

          ; Nothing should have been persisted for this catalog
          (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
                 [{:nrows 0}]))))

      (testing "on input that violates referential integrity"
        ; This catalog has an edge that points to a non-existant resource
        (let [catalog {:certname "myhost.mydomain.com"
                       :cmdb-version cat/CMDB-VERSION
                       :api-version 1
                       :version 123456789
                       :tags #{"class" "foobar"}
                       :classes #{"foobar"}
                       :edges #{{:source {:type "Class" :title "foobar"}
                                 :target {:type "File" :title "does not exist"}
                                 :relationship :contains}}
                       :resources {{:type "Class" :title "foobar"} {:type "Class" :title "foobar" :exported false}
                                   {:type "File" :title "/etc/foobar"} {:type       "File"
                                                                        :title      "/etc/foobar"
                                                                        :exported   false
                                                                        :tags       #{"file" "class" "foobar"}
                                                                        :parameters {"ensure" "directory"
                                                                                     "group"  "root"
                                                                                     "user"   "root"}}}}]
          (sql/with-connection *db*
            (initialize-store)
            (is (thrown? AssertionError (add-catalog! {})))

            ; Nothing should have been persisted for this catalog
            (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
                   [{:nrows 0}]))))))))
