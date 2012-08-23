(ns com.puppetlabs.puppetdb.test.scf.storage
  (:require [com.puppetlabs.puppetdb.catalog.utils :as catutils]
            [clojure.java.jdbc :as sql]
            [cheshire.core :as json])
  (:use [com.puppetlabs.puppetdb.examples]
        [com.puppetlabs.puppetdb.scf.storage]
        [com.puppetlabs.puppetdb.scf.migrate :only  [migrate!]]
        [clojure.test]
        [clojure.math.combinatorics :only (combinations subsets)]
        [clj-time.core :only [ago from-now now days]]
        [clj-time.coerce :only [to-timestamp]]
        [com.puppetlabs.jdbc :only [query-to-vec with-transacted-connection]]
        [com.puppetlabs.puppetdb.testutils :only [test-db]]))

(def db (test-db))

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
      (let [candidates (set (repeatedly 5 catutils/random-kw-resource))
            pairs      (combinations candidates 2)]
        (doseq [[r1 r2] pairs]
          (is (not= (resource-identity-hash r1)
                    (resource-identity-hash r2))))))))

(deftest catalog-dedupe
  (testing "Catalogs with the same metadata but different content should have different hashes"
    (let [catalog       (:basic catalogs)
          hash          (catalog-similarity-hash catalog)
          ;; List of all the tweaking functions
          chaos-monkeys [catutils/add-random-resource-to-catalog
                         catutils/mod-resource-in-catalog
                         catutils/mod-resource-metadata-in-catalog
                         catutils/add-random-edge-to-catalog
                         catutils/swap-edge-targets-in-catalog]
          ;; Function that will apply a random tweak function
          apply-monkey  #((rand-nth chaos-monkeys) %)]

      (is (not= hash (catalog-similarity-hash (catutils/add-random-resource-to-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/mod-resource-in-catalog catalog))))
      (is (not= hash (catalog-similarity-hash (catutils/mod-resource-metadata-in-catalog catalog))))
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

  (testing "Catalogs with different metadata but the same content should have the same hashes"
    (let [catalog            (:basic catalogs)
          hash               (catalog-similarity-hash catalog)
          ;; Functions that tweak various attributes of a catalog
          tweak-api-version  #(assoc % :api-version (inc (:api-version %)))
          tweak-version      #(assoc % :version (str (:version %) "?"))
          tweak-puppetdb-version #(assoc % :puppetdb-version (inc (:puppetdb-version %)))
          ;; List of all the tweaking functions
          chaos-monkeys      [tweak-api-version tweak-version tweak-puppetdb-version]
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
      (with-transacted-connection db
        (migrate!)
        (add-certname! certname)
        (add-facts! certname facts (now))
        (testing "should have entries for each fact"
          (is (= (query-to-vec "SELECT certname, fact, value FROM certname_facts ORDER BY fact")
                 [{:certname certname :fact "domain" :value "mydomain.com"}
                  {:certname certname :fact "fqdn" :value "myhost.mydomain.com"}
                  {:certname certname :fact "hostname" :value "myhost"}
                  {:certname certname :fact "kernel" :value "Linux"}
                  {:certname certname :fact "operatingsystem" :value "Debian"}])))
        (testing "should add the certname if necessary"
          (is (= (query-to-vec "SELECT name FROM certnames")
                 [{:name certname}])))
        (testing "replacing facts"
          (let [new-facts {"domain" "mynewdomain.com"
                           "fqdn" "myhost.mynewdomain.com"
                           "hostname" "myhost"
                           "kernel" "Linux"
                           "uptime_seconds" "3600"}]
            (replace-facts! {"name"  certname "values" new-facts} (now))
            (testing "should have only the new facts"
              (is (= (query-to-vec "SELECT fact, value FROM certname_facts ORDER BY fact")
                     [{:fact "domain" :value "mynewdomain.com"}
                      {:fact "fqdn" :value "myhost.mynewdomain.com"}
                      {:fact "hostname" :value "myhost"}
                      {:fact "kernel" :value "Linux"}
                      {:fact "uptime_seconds" :value "3600"}])))))))))

(deftest catalog-persistence
  (testing "Persisted catalogs"
    (let [catalog  (:basic catalogs)
          certname (:certname catalog)]

      (with-transacted-connection db
        (migrate!)
        (add-certname! certname)
        (let [hash (add-catalog! catalog)]
          (associate-catalog-with-certname! hash certname (now)))

        (testing "should contain proper catalog metadata"
          (is (= (query-to-vec ["SELECT cr.certname, c.api_version, c.catalog_version FROM catalogs c, certname_catalogs cr WHERE cr.catalog=c.hash"])
                 [{:certname certname :api_version 1 :catalog_version "123456789"}])))

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
                   [{:certname certname :type "Class" :title "foobar"}
                    {:certname certname :type "File"  :title "/etc/foobar"}
                    {:certname certname :type "File"  :title "/etc/foobar/baz"}])))

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
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash (add-catalog! catalog)]
            (store-catalog-for-certname! catalog (now))

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name certname}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should share structure when duplicate catalogs are detected for the same host"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash (add-catalog! catalog)
                prev-dupe-num (.count (:duplicate-catalog metrics))
                prev-new-num  (.count (:new-catalog metrics))]

            ;; Do an initial replacement with the same catalog
            (store-catalog-for-certname! catalog (now))
            (is (= 1 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
            (is (= 0 (- (.count (:new-catalog metrics)) prev-new-num)))

            ;; Store a second catalog, with the same content save the version
            (store-catalog-for-certname! (assoc catalog :version "abc123") (now))
            (is (= 2 (- (.count (:duplicate-catalog metrics)) prev-dupe-num)))
            (is (= 0 (- (.count (:new-catalog metrics)) prev-new-num)))

            (is (= (query-to-vec ["SELECT name FROM certnames"])
                   [{:name certname}]))

            (is (= (query-to-vec ["SELECT certname FROM certname_catalogs"])
                   [{:certname certname}
                    {:certname certname}]))

            (is (= (query-to-vec ["SELECT hash FROM catalogs"])
                   [{:hash hash}])))))

      (testing "should not fail when inserting an 'empty' catalog"
        (with-transacted-connection db
          (migrate!)
          (add-catalog! (:empty catalogs))))

      (testing "should noop if replaced by themselves after using manual deletion"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (add-catalog! catalog)
          (delete-catalog! certname)
          (add-catalog! catalog)

          (is (= (query-to-vec ["SELECT name FROM certnames"])
                 [{:name certname}]))))

      (testing "should be removed when deleted"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
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
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (add-catalog! catalog)
          (delete-catalog! certname)

          (is (= (query-to-vec ["SELECT name FROM certnames"])
                 [{:name certname}]))))

      (testing "when deleted, should leave other catalogs' resources alone"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (add-certname! "myhost2.mydomain.com")
          (let [hash1 (add-catalog! catalog)
                ;; Store a similar catalog for a different host
                hash2 (add-catalog! (-> catalog
                                      (assoc :certname "myhost2.mydomain.com")
                                      (update-in [:tags] conj "other_catalog")))]
            (associate-catalog-with-certname! hash1 certname (now))
            (associate-catalog-with-certname! hash2 "myhost2.mydomain.com" (now))
            (delete-catalog! hash1)

            ;; myhost should still be present in the database
            (is (= (query-to-vec ["SELECT name FROM certnames ORDER BY name"])
                   [{:name certname} {:name "myhost2.mydomain.com"}]))

            ;; myhost1 should not have any catalogs associated with it
            ;; anymore
            (is (= (query-to-vec ["SELECT certname, catalog FROM certname_catalogs"])
                   [{:certname "myhost2.mydomain.com" :catalog hash2}]))

            ;; no tags for myhost
            (is (= (query-to-vec [(str "SELECT t.name FROM tags t, certname_catalogs cc "
                                       "WHERE t.catalog=cc.catalog AND cc.certname=?")
                                  certname])
                   []))

            ;; no classes for myhost
            (is (= (query-to-vec [(str "SELECT c.name FROM classes c, certname_catalogs cc "
                                       "WHERE c.catalog=cc.catalog AND cc.certname=?")
                                  certname])
                   []))

            ;; no edges for myhost
            (is (= (query-to-vec [(str "SELECT COUNT(*) as c FROM edges e, certname_catalogs cc "
                                       "WHERE e.catalog=cc.catalog AND cc.certname=?")
                                  certname])
                   [{:c 0}]))

            ;; All the other resources should still be there
            (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalog_resources"])
                   [{:c 3}])))))

      (testing "when deleted without GC, should leave params"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 certname (now))
            (delete-catalog! hash1))

          ;; All the params should still be there
          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM resource_params"])
                 [{:c 7}]))))

      (testing "when deleted and GC'ed, should leave no dangling params or edges"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 certname (now))
            (delete-catalog! hash1))
          (garbage-collect!)

          (is (= (query-to-vec ["SELECT * FROM resource_params"])
                 []))
          (is (= (query-to-vec ["SELECT * FROM edges"])
                 []))))

      (testing "when dissociated and not GC'ed, should still exist"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 certname (now))
            (dissociate-catalog-with-certname! hash1 certname))

          (is (= (query-to-vec ["SELECT * FROM certname_catalogs"])
                 []))

          (is (= (query-to-vec ["SELECT COUNT(*) as c FROM catalogs"])
                 [{:c 1}]))))

      (testing "when dissociated and GC'ed, should no longer exist"
        (with-transacted-connection db
          (migrate!)
          (add-certname! certname)
          (let [hash1 (add-catalog! catalog)]
            (associate-catalog-with-certname! hash1 certname (now))
            (dissociate-catalog-with-certname! hash1 certname))
          (garbage-collect!)

          (is (= (query-to-vec ["SELECT * FROM certname_catalogs"])
                 []))

          (is (= (query-to-vec ["SELECT * FROM catalogs"])
                 [])))))

    (testing "should noop"

      (testing "on bad input"
        (with-transacted-connection db
          (migrate!)
          (is (thrown? AssertionError (add-catalog! {})))

          ; Nothing should have been persisted for this catalog
          (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
                 [{:nrows 0}]))))

      (testing "on input that violates referential integrity"
        ; This catalog has an edge that points to a non-existant resource
        (let [catalog (:invalid catalogs)]
          (with-transacted-connection db
            (migrate!)
            (is (thrown? AssertionError (add-catalog! {})))

            ; Nothing should have been persisted for this catalog
            (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
                   [{:nrows 0}]))))))))

(deftest node-deactivation
  (with-transacted-connection db
    (migrate!)
    (let [certname        "foo.example.com"
          query-certnames #(query-to-vec ["select name, deactivated from certnames"])
          deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]
      (add-certname! certname)

      (testing "deactivating a node"
        (testing "should mark the node as deactivated"
          (deactivate-node! certname)
          (let [result (first (query-certnames))]
            (is (= certname (:name result)))
            (is (deactivated? result))))

        (testing "should not change the node if it's already inactive"
          (let [original (query-certnames)]
            (deactivate-node! certname)
            (is (= original (query-certnames))))))

      (testing "activating a node"
        (testing "should activate the node if it was inactive"
          (activate-node! certname)
          (is (= (query-certnames) [{:name certname :deactivated nil}])))

        (testing "should do nothing if the node is already active"
          (let [original (query-certnames)]
            (activate-node! certname)
            (is (= original (query-certnames))))))

      (testing "auto-reactivated based on a command"
        (let [before-deactivating (to-timestamp (ago (days 1)))
              after-deactivating  (to-timestamp (from-now (days 1)))]
          (testing "should activate the node if the command happened after it was deactivated"
            (deactivate-node! certname)
            (is (= true (maybe-activate-node! certname after-deactivating)))
            (is (= (query-certnames) [{:name certname :deactivated nil}])))

          (testing "should not activate the node if the command happened before it was deactivated"
            (deactivate-node! certname)
            (is (= false (maybe-activate-node! certname before-deactivating)))
            (let [result (first (query-certnames))]
              (is (= certname (:name result)))
              (is (deactivated? result))))

          (testing "should do nothing if the node is already active"
            (activate-node! certname)
            (is (= true (maybe-activate-node! certname (now))))
            (is (= (query-certnames) [{:name certname :deactivated nil}]))))))))

(deftest node-staleness
  (testing "retrieving stale nodes based on age"
    (let [query-certnames #(query-to-vec ["select name, deactivated from certnames order by name"])
          deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]

      (testing "should return nothing if all nodes are more recent than max age"
        (with-transacted-connection db
          (migrate!)
          (let [catalog (:empty catalogs)
                certname (:certname catalog)]
            (add-certname! certname)
            (store-catalog-for-certname! catalog (now))
              (is (= (stale-nodes (ago (days 1))) [])))))

      (testing "should return nodes with a mixture of stale catalogs and facts (or neither)"
        (let [mutators [#(store-catalog-for-certname! (assoc (:empty catalogs) :certname "node1") (ago (days 2)))
                        #(replace-facts! {"name" "node1" "values" {"foo" "bar"}} (ago (days 2)))]]
          (doseq [func-set (subsets mutators)]
            (with-transacted-connection db
              (migrate!)
              (add-certname! "node1")
              (dorun (map #(%) func-set))
              (is (= (stale-nodes (ago (days 1))) ["node1"]))))))

      (testing "should only return nodes older than max age, and leave others alone"
        (with-transacted-connection db
          (migrate!)
          (let [catalog (:empty catalogs)]
            (add-certname! "node1")
            (add-certname! "node2")
            (store-catalog-for-certname! (assoc catalog :certname "node1") (ago (days 2)))
            (store-catalog-for-certname! (assoc catalog :certname "node2") (now))

            (is (= (set (stale-nodes (ago (days 1)))) #{"node1"}))))))))
