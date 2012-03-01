(ns com.puppetlabs.cmdb.test.scf.storage
  (:require [com.puppetlabs.cmdb.catalog :as cat]
            [com.puppetlabs.cmdb.catalog.utils :as catutils]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json])
  (:use [com.puppetlabs.cmdb.scf.storage]
        [com.puppetlabs.cmdb.scf.migrate :only [migrate!]]
        [clojure.test]
        [clojure.math.combinatorics :only (combinations)]
        [com.puppetlabs.jdbc :only [query-to-vec]]
        [com.puppetlabs.cmdb.testutils :only [test-db]]))

(def db (test-db))

(def empty-catalog
  {:certname "simple.mydomain.com"
   :cmdb-version cat/CMDB-VERSION
   :api-version 1
   :version "1330463884"
   :tags #{"settings"}
   :classes #{"settings"}
   :edges #{{:source {:type "Stage" :title "main"}
             :target {:type "Class" :title "Settings"}
             :relationship :contains}
            {:source {:type "Stage" :title "main"}
             :target {:type "Class" :title "Main"}
             :relationship :contains}}
   :resources {{:type "Class" :title "Main"} {:exported false
                                              :title      "Main"
                                              :tags       #{"class" "main"}
                                              :type       "Class"
                                              :parameters {"name" "main"}}
               {:type "Class" :title "Settings"} {:exported false
                                                  :title    "Settings"
                                                  :tags     #{"settings" "class"}
                                                  :type     "Class"}
               {:type "Stage" :title "main"} {:exported false
                                              :title    "main"
                                              :tags     #{"main" "stage"}
                                              :type     "Stage"}}
   :aliases {}})

(def basic-catalog
  {:certname "myhost.mydomain.com"
   :cmdb-version cat/CMDB-VERSION
   :api-version 1
   :version "123456789"
   :tags #{"class" "foobar"}
   :classes #{"foobar" "baz"}
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
                                                                     "require" "File[/etc/foobar]"}}}})

(deftest serialization
  (let [values ["foo" 0 "0" nil "nil" "null" [1 2 3] ["1" "2" "3"] {"a" 1 "b" [1 2 3]}]]
    (testing "serialized values should deserialize to the initial value"
      (doseq [value values]
        (is (= (json/parse-string (db-serialize value)) value))))
    (testing "serialized values should be unique"
      (doseq [value1 values
              value2 values]
        (let [str1 (db-serialize value1)
              str2 (db-serialize value2)]
          (when (= value1 value2)
            (is (= str1 str2)))
          (when-not (= value1 value2)
            (is (not= str1 str2)
              (str value1 " should not serialize the same as " value2))))))))

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
              :let [r (catutils/random-kw-resource)]]
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
      (let [candidates (into #{} (repeatedly 5 catutils/random-kw-resource))
            pairs      (combinations candidates 2)]
        (doseq [[r1 r2] pairs]
          (is (not= (resource-identity-hash r1)
                    (resource-identity-hash r2))))))))

(deftest catalog-dedupe
  (testing "Catalogs with different metadata but the same content should hash to the same thing"
    (let [catalog       basic-catalog
          hash          (catalog-similarity-hash catalog)
          ;; List of all the tweaking functions
          chaos-monkeys [catutils/add-random-resource-to-catalog
                         catutils/mod-resource-in-catalog
                         catutils/add-random-edge-to-catalog
                         catutils/swap-edge-targets-in-catalog]
          ;; Function that will apply a random tweak function
          apply-monkey  #((rand-nth chaos-monkeys) %)]

      (is (not= hash (catalog-similarity-hash (catutils/add-random-resource-to-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/mod-resource-in-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/add-random-edge-to-catalog catalog))))

      ;; Do the following 100 times: pick up to 10 tweaking functions,
      ;; successively apply them all to the original catalog, and
      ;; verify that the hash of the resulting catalog is the same as
      ;; the hash of the original catalog
      (doseq [nmonkeys (repeatedly 100 #(inc (rand-int 10)))
              :let [tweaked-catalog (nth (iterate apply-monkey catalog) nmonkeys)
                    tweaked-hash    (catalog-similarity-hash tweaked-catalog)]]
        (if (= catalog tweaked-catalog)
          (is (= hash tweaked-hash)
              (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))
          (is (not= hash tweaked-hash)
              (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))))))

  (testing "Catalogs with the same metadata but the different content should have different hashes"
    (let [catalog            basic-catalog
          hash               (catalog-similarity-hash catalog)
          ;; Functions that tweak various attributes of a catalog
          tweak-api-version  #(assoc % :api-version (inc (:api-version %)))
          tweak-version      #(assoc % :version (str (:version %) "?"))
          tweak-cmdb-version #(assoc % :cmdb-version (inc (:cmdb-version %)))
          ;; List of all the tweaking functions
          chaos-monkeys      [tweak-api-version tweak-version tweak-cmdb-version]
          ;; Function that will apply a random tweak function
          apply-monkey       #((rand-nth chaos-monkeys) %)]

      ;; Do the following 100 times: pick up to 10 tweaking functions,
      ;; successively apply them all to the original catalog, and
      ;; verify that the hash of the resulting catalog is the same as
      ;; the hash of the original catalog
      (doseq [nmonkeys (repeatedly 100 #(inc (rand-int 10)))
              :let [tweaked-catalog (nth (iterate apply-monkey catalog) nmonkeys)
                    tweaked-hash    (catalog-similarity-hash tweaked-catalog)]]
        (is (= hash tweaked-hash)
            (str catalog "\n has hash: " hash "\n and \n" tweaked-catalog "\n has hash: " tweaked-hash))))))

(deftest fact-persistence
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}]
      (sql/with-connection db
        (migrate!)
        (add-certname! certname)
        (add-facts! certname facts)
        (testing "should have entries for each fact"
          (is (= (query-to-vec "SELECT certname, fact, value FROM certname_facts ORDER BY fact")
                 [{:certname certname :fact "domain" :value "mydomain.com"}
                  {:certname certname :fact "fqdn" :value "myhost.mydomain.com"}
                  {:certname certname :fact "hostname" :value "myhost"}
                  {:certname certname :fact "kernel" :value "Linux"}
                  {:certname certname :fact "operatingsystem" :value "Debian"}])))
        (testing "should add the certname if necessary"
          (is (= (query-to-vec "SELECT name FROM certnames"))
                 [{:name certname}]))
        (testing "replacing facts"
          (let [new-facts {"domain" "mynewdomain.com"
                           "fqdn" "myhost.mynewdomain.com"
                           "hostname" "myhost"
                           "kernel" "Linux"
                           "uptime_seconds" "3600"}]
            (replace-facts! certname new-facts)
            (testing "should have only the new facts"
              (is (= (query-to-vec "SELECT fact, value FROM certname_facts ORDER BY fact")
                     [{:fact "domain" :value "mynewdomain.com"}
                      {:fact "fqdn" :value "myhost.mynewdomain.com"}
                      {:fact "hostname" :value "myhost"}
                      {:fact "kernel" :value "Linux"}
                      {:fact "uptime_seconds" :value "3600"}])))))))))

(deftest catalog-persistence
  (testing "Persisted catalogs"
    (let [catalog basic-catalog]

      (sql/with-connection db
        (migrate!)
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
                 [{:name "baz"} {:name "foobar"}])))

        (testing "should contain a complete edges list"
          (is (= (query-to-vec [(str "SELECT r1.type as stype, r1.title as stitle, r2.type as ttype, r2.title as ttitle, e.type as etype "
                                     "FROM edges e, catalog_resources r1, catalog_resources r2 "
                                     "WHERE e.source=r1.resource AND e.target=r2.resource "
                                     "ORDER BY r1.type, r1.title, r2.type, r2.title, e.type")])
                 [{:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar" :etype "contains"}
                  {:stype "Class" :stitle "foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "contains"}
                  {:stype "File" :stitle "/etc/foobar" :ttype "File" :ttitle "/etc/foobar/baz" :etype "required-by"}])))

        (testing "should contain a complete resources list"
          (is (= (query-to-vec ["SELECT type, title FROM catalog_resources ORDER BY type, title"])
                 [{:type "Class" :title "foobar"}
                  {:type "File" :title "/etc/foobar"}
                  {:type "File" :title "/etc/foobar/baz"}]))

          (testing "properly associated with the host"
            (is (= (query-to-vec ["SELECT cc.certname, cr.type, cr.title FROM catalog_resources cr, certname_catalogs cc WHERE cc.catalog=cr.catalog ORDER BY cr.type, cr.title"])
                   [{:certname "myhost.mydomain.com" :type "Class" :title "foobar"}
                    {:certname "myhost.mydomain.com" :type "File"  :title "/etc/foobar"}
                    {:certname "myhost.mydomain.com" :type "File"  :title "/etc/foobar/baz"}])))

          (testing "with all parameters"
            (is (= (query-to-vec ["SELECT cr.type, cr.title, rp.name, rp.value FROM catalog_resources cr, resource_params rp WHERE rp.resource=cr.resource ORDER BY cr.type, cr.title, rp.name"])
                   [{:type "File" :title "/etc/foobar" :name "ensure" :value (db-serialize "directory")}
                    {:type "File" :title "/etc/foobar" :name "group" :value (db-serialize "root")}
                    {:type "File" :title "/etc/foobar" :name "user" :value (db-serialize "root")}
                    {:type "File" :title "/etc/foobar/baz" :name "ensure" :value (db-serialize "directory")}
                    {:type "File" :title "/etc/foobar/baz" :name "group" :value (db-serialize "root")}
                    {:type "File" :title "/etc/foobar/baz" :name "require" :value (db-serialize "File[/etc/foobar]")}
                    {:type "File" :title "/etc/foobar/baz" :name "user" :value (db-serialize "root")}])))

          (testing "with all metadata"
            (let [result (query-to-vec ["SELECT cr.type, cr.title, cr.exported, cr.tags, cr.sourcefile, cr.sourceline FROM catalog_resources cr ORDER BY cr.type, cr.title"])]
              (is (= (map #(assoc % :tags (sort (:tags %))) result)
                     [{:type "Class" :title "foobar" :tags [] :exported false :sourcefile nil :sourceline nil}
                      {:type "File" :title "/etc/foobar" :tags ["class" "file" "foobar"] :exported false :sourcefile "/tmp/foo" :sourceline 10}
                      {:type "File" :title "/etc/foobar/baz" :tags ["class" "file" "foobar"] :exported false :sourcefile "/tmp/bar" :sourceline 20}]))))))

      (testing "should noop if replaced by themselves"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (let [hash (add-catalog! catalog)]
            (replace-catalog! catalog)

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should share structure when duplicate catalogs are detected for the same host"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (let [hash (add-catalog! catalog)
                prev-dupe-num (.count (:duplicate-catalog metrics))
                prev-new-num  (.count (:new-catalog metrics))]

            ;; Do an initial replacement with the same catalog
            (replace-catalog! catalog)
            (is (= 1 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
            (is (= 0 (- (.count (:new-catalog metrics)) prev-new-num)))

            ;; Store a second catalog, with the same content save the version
            (replace-catalog! (assoc catalog :version "abc123"))
            (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
            (is (= 0 (- (.count (:new-catalog metrics)) prev-new-num)))

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT certname FROM certname_catalogs"])
                   [{:certname "myhost.mydomain.com"}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should not fail when inserting an 'empty' catalog"
        (sql/with-connection db
          (migrate!)
          (add-catalog! empty-catalog)))

      (testing "should noop if replaced by themselves after using manual deletion"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (add-catalog! catalog)
          (delete-catalog! "myhost.mydomain.com")
          (add-catalog! catalog)

          (is (= (query-to-vec ["SELECT name FROM certnames"])
                 [{:name "myhost.mydomain.com"}]))))

      (testing "should be removed when deleted"
        (sql/with-connection db
          (migrate!)
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

      (testing "when deleted, should leave certnames alone"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (add-catalog! catalog)
          (delete-catalog! "myhost.mydomain.com")

          (is (= (query-to-vec ["SELECT * FROM certnames"])
                 [{:name "myhost.mydomain.com"}]))))

      (testing "when deleted, should leave other hosts' resources alone"
        (sql/with-connection db
          (migrate!)
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

          ;; All the other resources should still be there
          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalog_resources"])
                 [{:c 3}]))))

      (testing "when deleted without GC, should leave params"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (delete-catalog! hash1))

          ;; All the params should still be there
          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params"])
                 [{:c 7}]))))

      (testing "when deleted and GC'ed, should leave no dangling params or edges"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (delete-catalog! hash1))
          (garbage-collect!)

          (is (= (query-to-vec ["SELECT * FROM resource_params"])
                 []))
          (is (= (query-to-vec ["SELECT * FROM edges"])
                 []))))

      (testing "when dissociated and not GC'ed, should still exist"
        (sql/with-connection db
          (migrate!)
          (add-certname! "myhost.mydomain.com")
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 "myhost.mydomain.com")
            (dissociate-catalog-with-certname! hash1 "myhost.mydomain.com"))

          (is (= (query-to-vec ["SELECT * FROM certname_catalogs"])
                 []))

          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalogs"])
                 [{:c 1}]))))

      (testing "when dissociated and GC'ed, should no longer exist"
        (sql/with-connection db
          (migrate!)
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
        (sql/with-connection db
          (migrate!)
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
          (sql/with-connection db
            (migrate!)
            (is (thrown? AssertionError (add-catalog! {})))

            ; Nothing should have been persisted for this catalog
            (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
                   [{:nrows 0}]))))))))
