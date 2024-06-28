(ns puppetlabs.puppetdb.scf.storage-test
  (:require
   [clojure.java.jdbc :as sql]
   [metrics.timers]
   [puppetlabs.puppetdb.cheshire :as json]
   [puppetlabs.puppetdb.reports :as report]
   [puppetlabs.puppetdb.scf.hash :as shash]
   [puppetlabs.puppetdb.facts :as facts]
   [puppetlabs.puppetdb.schema :as pls :refer [defn-validated]]
   [clojure.walk :as walk]
   [puppetlabs.puppetdb.scf.storage-utils :as sutils]
   [puppetlabs.kitchensink.core :as kitchensink]
   [puppetlabs.puppetdb.testutils :as tu]
   [puppetlabs.puppetdb.testutils.db
    :refer [*db* clear-db-for-testing! init-db with-test-db]]
   [metrics.histograms :refer [sample histogram]]
   [metrics.counters :as counters]
   [schema.core :as s]
   [clojure.string :as str]
   [puppetlabs.puppetdb.examples :refer [catalogs]]
   [puppetlabs.puppetdb.examples.reports :refer [reports]]
   [puppetlabs.puppetdb.testutils.reports
    :refer [is-latest-report? store-example-report!]]
   [puppetlabs.puppetdb.testutils.events :refer [query-resource-events]]
   [puppetlabs.puppetdb.testutils.nodes :refer [node-for-certname]]
   [puppetlabs.puppetdb.random :as random]
   [puppetlabs.puppetdb.scf.partitioning :refer [get-partition-names]]
   [puppetlabs.puppetdb.scf.storage :as scf-storage
    :refer [*note-add-params-insert*
            *note-insert-catalog-resources-insert*
            acquire-locks!
            add-certname!
            add-facts!
            basic-diff
            call-with-lock-timeout
            catalog-edges-map
            catalog-schema
            certname-factset-metadata
            deactivate-node!
            delete-certname!
            delete-reports-older-than!
            delete-unassociated-packages!
            delete-unused-fact-paths
            diff-resources-metadata
            ensure-environment
            ensure-producer
            environment-id
            expire-stale-nodes
            have-newer-record-for-certname?
            insert-packages
            maybe-activate-node!
            merge-resource-hash
            normalize-report
            purge-deactivated-and-expired-nodes!
            realize-paths
            replace-catalog!
            replace-catalog-inputs!
            replace-edges!
            replace-facts!
            resources-exist?
            set-certname-facts-expiration
            status-id
            storage-metrics
            storage-metrics-registry
            timestamp-of-newest-record
            update-facts!]]
   [clojure.test :refer :all]
   [clojure.math.combinatorics :refer [subsets]]
   [puppetlabs.puppetdb.jdbc :as jdbc
    :refer [call-with-query-rows query-to-vec]]
   [puppetlabs.puppetdb.time :as time
    :refer [ago days from-now now to-string to-timestamp]])
  (:import
   (clojure.lang ExceptionInfo)
   (java.sql SQLException)))

(def reference-time "2014-10-28T20:26:21.727Z")
(def previous-time "2014-10-26T20:26:21.727Z")

(defn-validated expire-node!
  "Expire the given host, recording expire-time. If the node is
  already expired, no change is made."
  [certname :- String expire-time :- pls/Timestamp]
  (jdbc/do-prepared
   "update certnames_status set expired = ? where certname=? and expired is null"
   [(to-timestamp expire-time) certname]))

;; When only one db is needed.
(defmacro deftest-db [name & body]
  `(deftest ~name (with-test-db ~@body)))

(deftest-db ensure-producer-test
  (let [prod1 "foo.com"
        prod2 "bar.com"]
    (ensure-producer prod1)
    (testing "doesn't create new row for existing producer"
      (is (= 1 (ensure-producer prod1))))
    (testing "creates new row for non-existing producer"
      (is (= 2 (ensure-producer prod2))))))

(defn-validated factset-map :- {s/Str s/Any}
  "Return all facts and their values for a given certname as a map"
  [certname :- String]
  (or (-> (jdbc/query ["select (stable||volatile) as facts from factsets where certname=?"
                       certname])
          first
          :facts
          str
          json/parse-string)
      {}))

(defn stable-facts [certname]
  (-> (query-to-vec "select stable from factsets where certname=?" certname)
      first
      :stable
      str
      json/parse-string))

(defn volatile-facts [certname]
  (-> (query-to-vec "select volatile from factsets where certname=?" certname)
      first
      :volatile
      str
      json/parse-string))

(defn count-facts
  []
  (-> "select count(*) c from (select jsonb_each(stable||volatile) from factsets) fs"
      query-to-vec
      first
      :c))

(defn new-facts-time []
  (metrics.timers/number-recorded  (:add-new-fact @storage-metrics)))

(deftest-db large-fact-update
  (testing "updating lots of facts"
    (let [certname "scale.com"
          facts1 (zipmap (take 10000 (repeatedly #(random/random-string 10)))
                         (take 10000 (repeatedly #(random/random-string 10))))
          timestamp1 (-> 2 days ago)
          facts2 (zipmap (take 11000 (repeatedly #(random/random-string 10)))
                         (take 11000 (repeatedly #(random/random-string 10))))
          timestamp2 (-> 1 days ago)
          producer "bar.com"
          old-facts-time (new-facts-time)]
      (add-certname! certname)

      (add-facts! {:certname certname
                   :values facts1
                   :timestamp timestamp1
                   :environment nil
                   :producer_timestamp timestamp1
                   :producer producer})

      (testing "new-fact-time metric is updated"
          (is (= 1 (- (new-facts-time) old-facts-time))))

      (testing "10000 facts stored"
        (is (= 10000 (count-facts))))

      (update-facts! {:certname certname
                      :values facts2
                      :timestamp timestamp2
                      :environment nil
                      :producer_timestamp timestamp2
                      :producer producer})

      (testing "11000 facts stored"
        (is (= 11000 (count-facts)))))))

(deftest-db escaped-string-factnames
  (testing "should work with escaped strings"
    (let [certname "some_certname"
          facts {"\"hello\"" "world"
                 "foo#~bar" "baz"
                 "\"foo" "bar"
                 "foo#~" "bar"
                 "foo" "bar"}
          producer "bar.com"]
      (add-certname! certname)

      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment nil
                   :producer_timestamp previous-time
                   :producer producer})
      (is (= facts (factset-map "some_certname"))))))

(defn delete-certname-facts!
  [certname]
  (jdbc/do-prepared "delete from factsets where certname = ?" [certname]))

(deftest fact-persistence
  (with-test-db
    (testing "Persisted facts"
      (let [certname "some_certname"
            facts {"domain" "mydomain.com"
                   "fqdn" "myhost.mydomain.com"
                   "hostname" "myhost"
                   "kernel" "Linux"
                   "operatingsystem" "Debian"}
            producer "bar.com"]
        (add-certname! certname)

        (is (nil?
             (jdbc/with-db-transaction []
               (timestamp-of-newest-record :factsets "some_certname"))))
        (is (empty? (factset-map "some_certname")))

        (add-facts! {:certname certname
                     :values facts
                     :timestamp previous-time
                     :environment nil
                     :producer_timestamp previous-time
                     :producer producer})

        (testing "should have entries for each fact"
          (is (= facts (factset-map "some_certname"))))

        (testing "should have entries for each fact"
          (is (= facts (factset-map certname)))
          (is (jdbc/with-db-transaction []
                (timestamp-of-newest-record :factsets  "some_certname")))
          (is (= facts (factset-map "some_certname"))))

        (testing "should add the certname if necessary"
          (is (= (query-to-vec "SELECT certname FROM certnames")
                 [{:certname certname}])))

        (testing "should start with no volatile facts"
          (is (= facts (stable-facts certname)))
          (is (= {} (volatile-facts certname))))

        (testing "replacing facts"
          ;; Ensuring here that new records are inserted, updated
          ;; facts are updated (not deleted and inserted) and that
          ;; the necessary deletes happen
          (tu/with-wrapped-fn-args [updates jdbc/update!]
            (let [new-facts {"domain" "mynewdomain.com"
                             "fqdn" "myhost.mynewdomain.com"
                             "hostname" "myhost"
                             "kernel" "Linux"
                             "uptime_seconds" 3600}]
              (replace-facts! {:certname certname
                               :values new-facts
                               :environment "DEV"
                               :producer_timestamp reference-time
                               :timestamp reference-time
                               :producer producer})

              (testing "should have only the new facts"
                (is (= {"domain" "mynewdomain.com"
                        "fqdn" "myhost.mynewdomain.com"
                        "hostname" "myhost"
                        "kernel" "Linux"
                        "uptime_seconds" 3600}
                       (factset-map certname))))

              (testing "producer_timestamp should store current time"
                (is (= (query-to-vec "SELECT producer_timestamp FROM factsets")
                       [{:producer_timestamp (to-timestamp reference-time)}])))

              (testing "changed facts should now be volatile"
                (is (= #{"domain" "fqdn"}
                       (set (keys (volatile-facts certname))))))

              (testing "should update existing keys"
                (is (= 1 (count @updates)))
                (let [bytes->hex (fn [bytes]
                                   (->> bytes
                                       (map #(format "%02x" (bit-and 0xff %)))
                                       (apply str)))
                      expected-ts (to-timestamp reference-time)
                      [[_ m _params]] @updates]
                  (is (= {:timestamp expected-ts
                          :producer_timestamp expected-ts
                          :environment_id 1
                          :producer_id 1
                          :hash "1a4b10a865b8c7b435ec0fe06968fdc62337f57f"
                          :paths_hash "2b34e4a4e24b4fca5fc154d2700fc2a430c6a1a1"
                          :stable_hash "33c9461b3b37541edec996529c3f6aba3c2ea0e4"
                          :stable {:hostname "myhost" :kernel "Linux" :uptime_seconds 3600}
                          :volatile {:domain "mynewdomain.com" :fqdn "myhost.mynewdomain.com"}}
                         (-> m
                             (update :hash sutils/parse-db-hash)
                             (update :paths_hash bytes->hex)
                             (update :stable_hash bytes->hex)
                             (update :stable sutils/parse-db-json)
                             (update :volatile sutils/parse-db-json)))))))))

        (testing "replacing all new facts"
          (delete-certname-facts! certname)
          (replace-facts! {:certname certname
                           :values facts
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (is (= facts (factset-map "some_certname"))))

        (testing "replacing all facts with new ones"
          (delete-certname-facts! certname)
          (add-facts! {:certname certname
                       :values facts
                       :timestamp previous-time
                       :environment nil
                       :producer_timestamp previous-time
                       :producer nil})
          (replace-facts! {:certname certname
                           :values {"foo" "bar"}
                           :environment "DEV"
                           :producer_timestamp (now)
                           :timestamp (now)
                           :producer producer})
          (is (= {"foo" "bar"} (factset-map "some_certname"))))

        (testing "replace-facts with only additions"
          (let [fact-map (factset-map "some_certname")]
            (replace-facts! {:certname certname
                             :values (assoc fact-map "one more" "here")
                             :environment "DEV"
                             :producer_timestamp (now)
                             :timestamp (now)
                             :producer producer})
            (is (= (assoc fact-map  "one more" "here")
                   (factset-map "some_certname")))))

        (testing "replace-facts with no change"
          (let [fact-map (factset-map "some_certname")]
            (replace-facts! {:certname certname
                             :values fact-map
                             :environment "DEV"
                             :producer_timestamp (now)
                             :timestamp (now)
                             :producer producer})
            (is (= fact-map
                   (factset-map "some_certname")))))
        (testing "stable hash when no facts change"
          (let [fact-map (factset-map "some_certname")
                {old-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
            (replace-facts! {:certname certname
                             :values fact-map
                             :environment "DEV"
                             :producer_timestamp (now)
                             :timestamp (now)
                             :producer producer})
            (let [{new-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
              (is (= old-hash new-hash)))
            (replace-facts! {:certname certname
                             :values (assoc fact-map "another thing" "goes here")
                             :environment "DEV"
                             :producer_timestamp (now)
                             :timestamp (now)
                             :producer producer})
            (let [{new-hash :hash} (first (query-to-vec (format "SELECT %s AS hash FROM factsets where certname=?" (sutils/sql-hash-as-str "hash")) certname))]
              (is (not= old-hash new-hash)))))))))

(deftest fact-path-gc
  (letfn [(facts-now [c v]
            {:certname c :values v
             :environment nil :timestamp (now) :producer_timestamp (now) :producer nil})
          (paths-and-types []
            (query-to-vec "select path, value_type_id from fact_paths"))
          (check-gc [factset-changes
                     expected-before
                     expected-after]
            (clear-db-for-testing!)
            (init-db *db*)
            (doseq [cert (set (map first factset-changes))]
              (add-certname! cert))
            (doseq [[cert factset] factset-changes]
              (replace-facts! (facts-now cert factset)))
            (let [obs (paths-and-types)]
              (is (= (count expected-before) (count obs)))
              (is (= expected-before (set obs))))
            (delete-unused-fact-paths)
            (let [obs (paths-and-types)]
              (is (= (count expected-after) (count obs)))
              (is (= expected-after (set obs)))))]
    (let [type-id {:int (facts/value-type-id 0)
                   :str (facts/value-type-id "0")
                   :obj (facts/value-type-id [])}]
      (with-test-db

        (testing "works when there are no paths"
          (check-gc [] #{} #{}))

        (testing "doesn't do anything if nothing changes"
          (let [before #{{:path "a" :value_type_id (type-id :int)}
                         {:path "b" :value_type_id (type-id :obj)}
                         {:path "b#~foo" :value_type_id (type-id :str)}}]
            (check-gc [["c-x" {"a" 1}]
                       ["c-y" {"b" {"foo" "two"}}]]
                      before
                      before)))

        (testing "orphaning of a simple scalar"
          (check-gc [["c-x" {"a" 1}]
                     ["c-y" {"b" 2}]
                     ["c-y" {"c" 3}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :int)}
                      {:path "c" :value_type_id (type-id :int)}}
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "c" :value_type_id (type-id :int)}}))

        (testing "orphaning of a structured fact"
          (check-gc [["c-x" {"a" 1}]
                     ["c-y" {"b" {"foo" "bar"}}]
                     ["c-y" {"c" 3}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~foo" :value_type_id (type-id :str)}
                      {:path "c" :value_type_id (type-id :int)}}
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "c" :value_type_id (type-id :int)}}))

        (testing "orphaning of an array"
          (check-gc [["c-x" {"a" 1}]
                     ["c-y" {"b" ["x" "y"]}]
                     ["c-y" {"c" 3}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~0" :value_type_id (type-id :str)}
                      {:path "b#~1" :value_type_id (type-id :str)}
                      {:path "c" :value_type_id (type-id :int)}}
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "c" :value_type_id (type-id :int)}}))

        ;; In these type change tests, orphaned types linger because
        ;; the current gc only operates on (removes) paths that have
        ;; no references at all.  It leaves any existing entries for a
        ;; given path alone.

        (testing "structured fact changing to simple"
          (check-gc [["c-x" {"b" {"foo" "bar"}}]
                     ["c-x" {"b" 1}]]
                    #{{:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~foo" :value_type_id (type-id :str)}
                      {:path "b" :value_type_id (type-id :int)}}
                    #{{:path "b" :value_type_id (type-id :obj)}
                      {:path "b" :value_type_id (type-id :int)}}))

        (testing "simple fact changing to structured"
          (check-gc [["c-x" {"b" 1}]
                     ["c-x" {"b" {"foo" "bar"}}]]
                    #{{:path "b" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~foo" :value_type_id (type-id :str)}}
                   #{{:path "b" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~foo" :value_type_id (type-id :str)}}))

        (testing "array changes to scalar"
          (check-gc [["c-x" {"b" ["x" "y"]}]
                     ["c-x" {"b" 1}]]
                    #{{:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~0" :value_type_id (type-id :str)}
                      {:path "b#~1" :value_type_id (type-id :str)}
                      {:path "b" :value_type_id (type-id :int)}}
                    #{{:path "b" :value_type_id (type-id :obj)}
                      {:path "b" :value_type_id (type-id :int)}}))

        (testing "scalar changes to array"
          (check-gc [["c-x" {"b" 1}]
                     ["c-x" {"b" ["x" "y"]}]]
                    #{{:path "b" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~0" :value_type_id (type-id :str)}
                      {:path "b#~1" :value_type_id (type-id :str)}}
                    #{{:path "b" :value_type_id (type-id :int)}
                      {:path "b" :value_type_id (type-id :obj)}
                      {:path "b#~0" :value_type_id (type-id :str)}
                      {:path "b#~1" :value_type_id (type-id :str)}}))

        (testing "multiple types for path and all disappear"
          (check-gc [["c-w" {"a" 1}]
                     ["c-x" {"a" "two"}]
                     ["c-y" {"a" {"foo" "bar"}}]
                     ["c-z" {"a" [3]}]
                     ["c-w" {}]
                     ["c-x" {}]
                     ["c-y" {}]
                     ["c-z" {}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "a" :value_type_id (type-id :str)}
                      {:path "a" :value_type_id (type-id :obj)}
                      {:path "a#~foo" :value_type_id (type-id :str)}
                      {:path "a#~0" :value_type_id (type-id :int)}}
                    #{}))

        (testing "multiple types for path and all types change"
          (check-gc [["c-x" {"a" 1}]
                     ["c-y" {"a" [0]}]
                     ["c-x" {"a" {"foo" "bar"}}]
                     ["c-y" {"a" "two"}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "a" :value_type_id (type-id :obj)}
                      {:path "a#~0" :value_type_id (type-id :int)}
                      {:path "a#~foo" :value_type_id (type-id :str)}
                      {:path "a" :value_type_id (type-id :str)}}
                    ;; Q: Why didn't "a" :int stick around?
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "a" :value_type_id (type-id :obj)}
                      {:path "a#~foo" :value_type_id (type-id :str)}
                      {:path "a" :value_type_id (type-id :str)}}))

        (testing "everything to nothing"
          (check-gc [["c-x" {"a" 1}]
                     ["c-y" {"a" ["x" "y"]}]
                     ["c-z" {"a" {"foo" "bar"}}]
                     ["c-x" {}]
                     ["c-y" {}]
                     ["c-z" {}]]
                    #{{:path "a" :value_type_id (type-id :int)}
                      {:path "a" :value_type_id (type-id :obj)}
                      {:path "a#~0" :value_type_id (type-id :str)}
                      {:path "a#~1" :value_type_id (type-id :str)}
                      {:path "a#~foo" :value_type_id (type-id :str)}}
                    #{}))))))

(deftest factset-paths-write-minimization
  (letfn [(facts-now [c v]
            {:certname c :values v
             :environment nil :timestamp (now) :producer_timestamp (now) :producer nil})
          (certname-paths-hash [certname]
            (-> "select paths_hash from factsets where certname = ?"
                (query-to-vec certname)
                first
                :paths_hash))
          (reset-db []
            (clear-db-for-testing!)
            (init-db *db*))
          (set-cert-facts-causes-update [cert factset]
            (let [real-realize-paths realize-paths
                  called? (atom false)]
              (with-redefs [realize-paths (fn [& args]
                                            (reset! called? true)
                                            (apply real-realize-paths args))]
                (replace-facts! (facts-now cert factset)))
              @called?))]
    (with-test-db

      (testing "with no hash, establishing no facts establishes a hash"
        (reset-db)
        (add-certname! "foo")
        (is (= nil (certname-paths-hash "foo")))
        (set-cert-facts-causes-update "foo" {})
        (let [hash (certname-paths-hash "foo")]
          (is (= 20 (count hash)))
          (is (= (class (byte-array 0)) (class hash)))))

      (testing "with hash for no paths, establishing no paths causes no update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {})
        (is (= false (set-cert-facts-causes-update "foo" {}))))

      (testing "with hash for no paths, establishing paths causes update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {})
        (is (= true (set-cert-facts-causes-update "foo" {"a" 1}))))

      (testing "with paths, replacing with same paths causes no update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {"a" 1})
        (is (= false (set-cert-facts-causes-update "foo" {"a" 1}))))

      (testing "with paths, changing fact values causes no update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {"a" 1})
        (is (= false (set-cert-facts-causes-update "foo" {"a" 2}))))

      (testing "with paths, changing paths causes update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {"a" 1})
        (is (= true (set-cert-facts-causes-update "foo" {"b" 1}))))

      (testing "with paths, adding path causes update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {"a" 1})
        (is (= true (set-cert-facts-causes-update "foo" {"a" 1 "b" 1}))))

      (testing "with paths, removing path causes update"
        (reset-db)
        (add-certname! "foo")
        (set-cert-facts-causes-update "foo" {"a" 1 "b" 1})
        (is (= true (set-cert-facts-causes-update "foo" {"b" 1})))))))

(deftest-db fact-persistance-with-environment
  (testing "Persisted facts"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}
          producer "bar.com"]
      (add-certname! certname)

      (is (nil?
           (jdbc/with-db-transaction []
            (timestamp-of-newest-record :factsets "some_certname"))))
      (is (empty? (factset-map "some_certname")))
      (is (nil? (environment-id "PROD")))

      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment "PROD"
                   :producer_timestamp previous-time
                   :producer producer})

      (testing "should have entries for each fact"
        (is (= facts (factset-map "some_certname")))

        (is (= [{:certname "some_certname"
                 :environment_id (environment-id "PROD")}]
               (query-to-vec "SELECT certname, environment_id FROM factsets"))))

      (is (nil? (environment-id "DEV")))

      (update-facts!
       {:certname certname
        :values facts
        :timestamp (-> 1 days ago)
        :environment "DEV"
        :producer_timestamp (-> 1 days ago)
        :producer producer})

      (testing "should have the same entries for each fact"
        (is (= facts (factset-map "some_certname")))
        (is (= [{:certname "some_certname"
                 :environment_id (environment-id "DEV")}]
               (query-to-vec "SELECT certname, environment_id FROM factsets")))))))

(defn package-seq
  "Return all facts and their values for a given certname as a map"
  [certname]
  (rest
   (jdbc/query
    ["SELECT p.name as package_name, p.version, p.provider
                  FROM certname_packages cp
                       inner join packages p on cp.package_id = p.id
                       inner join certnames c on cp.certname_id = c.id
                  WHERE c.certname = ?
                  ORDER BY package_name, version, provider"
     certname]
    {:as-arrays? true})))

(deftest-db fact-persistance-with-packages
  (testing "Existing facts with new packages added"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}
          producer "bar.com"]
      (add-certname! certname)

      (testing "Existing facts with new packages added"
        (add-facts! {:certname certname
                     :values facts
                     :timestamp previous-time
                     :environment "PROD"
                     :producer_timestamp previous-time
                     :producer producer})

        (is (empty? (package-seq certname)))

        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory [["foo" "1.2.3" "apt"]
                              ["bar" "2.3.4" "apt"]
                              ["baz" "3.4.5" "apt"]]})


        (is (= [["bar" "2.3.4" "apt"]
                ["baz" "3.4.5" "apt"]
                ["foo" "1.2.3" "apt"]]
               (package-seq certname))))

      (testing "Updating existing packages"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory [["foo" "1.2.3" "apt"]
                              ["bar" "2.3.4" "apt"]
                              ["not-baz" "3.4.5" "apt"]]})
        (is (= [["bar" "2.3.4" "apt"]
                ["foo" "1.2.3" "apt"]
                ["not-baz" "3.4.5" "apt"]]
               (package-seq certname))))

      (testing "Removing packages"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory [["foo" "1.2.3" "apt"]]})
        (is (= [["foo" "1.2.3" "apt"]]
               (package-seq certname))))

      (testing "Removing packages"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory [["foo-1" "1.2.3" "apt"]
                              ["foo-2" "1.2.3" "apt"]
                              ["foo-3" "1.2.3" "apt"]
                              ["foo-4" "1.2.3" "apt"]]})

        (is (= [["foo-1" "1.2.3" "apt"]
                ["foo-2" "1.2.3" "apt"]
                ["foo-3" "1.2.3" "apt"]
                ["foo-4" "1.2.3" "apt"]]
               (package-seq certname))))

      (testing "Pinpoint GC cleans up packages"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory [["foo-1" "1.2.3" "apt"]]})

        (is (= [["foo-1" "1.2.3" "apt"]]
               (package-seq certname)))


        (is (= 1
               (-> ["SELECT count(*) as c FROM packages"]
                   query-to-vec
                   first
                   :c))))

      (testing "Orphaned packages are deleted"
        (let [package-count (fn [] (-> ["SELECT count(*) as c FROM packages"]
                                       query-to-vec
                                       first
                                       :c))]
          (is (pos? (package-count)))
          (jdbc/do-commands "DELETE FROM certname_packages")
          (is (pos? (package-count)))
          (delete-unassociated-packages!)
          (is (zero? (package-count))))))))

(deftest-db purge-packages-from-node
  (testing "Existing facts with new packages added"
    (let [certname "some_certname"
          facts {"domain" "mydomain.com"
                 "fqdn" "myhost.mydomain.com"
                 "hostname" "myhost"
                 "kernel" "Linux"
                 "operatingsystem" "Debian"}
          producer "bar.com"
          reload-packages (fn []
                            (update-facts!
                             {:certname certname
                              :values facts
                              :timestamp (-> 1 days ago)
                              :environment "DEV"
                              :producer_timestamp (-> 1 days ago)
                              :producer producer
                              :package_inventory [["foo" "1.2.3" "apt"]
                                                  ["bar" "2.3.4" "apt"]
                                                  ["baz" "3.4.5" "apt"]]}))
          find-package-hash (fn []
                              (:package_hash (certname-factset-metadata "some_certname")))]
      (add-certname! certname)
      (add-facts! {:certname certname
                   :values facts
                   :timestamp previous-time
                   :environment "PROD"
                   :producer_timestamp previous-time
                   :producer producer})

      (is (empty? (package-seq certname)))

      (reload-packages)

      (testing "data was loaded for test"
        (is (= 3 (count (package-seq certname)))))

      (is (find-package-hash))

      (testing "package_inventory key is missing from command"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer})

        (is (= []
               (package-seq certname)))

        (is (nil? (find-package-hash))))

      (reload-packages)
      (is (= 3 (count (package-seq certname))))

      (testing "package_inventory is nil"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory nil})

        (is (= 0 (count (package-seq certname))))
        (is (nil? (find-package-hash))))

      (reload-packages)
      (is (= 3 (count (package-seq certname))))
      (is (find-package-hash))

      (testing "package_inventory is empty"
        (update-facts!
         {:certname certname
          :values facts
          :timestamp (-> 1 days ago)
          :environment "DEV"
          :producer_timestamp (-> 1 days ago)
          :producer producer
          :package_inventory []})

        (is (= 0 (count (package-seq certname))))
        (is (nil? (find-package-hash)))))))

(def catalog (:basic catalogs))
(def certname (:certname catalog))
(def current-time (str (now)))

(deftest resource-insert-exception-handler
  (let [small {:type "Class" :title "yo" :file "small" :line 42}
        large {:type "Class" :title (.repeat "yo" 10000) :file "large" :line 1337}
        validate-data (fn [{:keys [kind] :as data}]
                        (is (= ::scf-storage/resource-insert-limit-exceeded kind))
                        (is (= true (:puppetlabs.puppetdb/known-error? data))))
        ex-ex (SQLException. "?" (jdbc/sql-state :program-limit-exceeded))]
    (testing "certain culprits"
      (doseq [candidate [small [small]]]
        (try
          (scf-storage/handle-resource-insert-sql-ex ex-ex "foo" candidate)
          (assert false "expected exception not thrown")
          (catch ExceptionInfo ex
            (-> ex ex-data validate-data)
            (is (= "A catalog resource for certname \"foo\" is too large: {:file \"small\", :line 42}"
                   (ex-message ex)))))))
    (testing "no suspects"
      (try
        (scf-storage/handle-resource-insert-sql-ex ex-ex "foo" [small small])
        (assert false "expected exception not thrown")
        (catch ExceptionInfo ex
          (-> ex ex-data validate-data)
          (is (= "A catalog resource for certname \"foo\" is too large"
                 (ex-message ex))))))
    (testing "multiple suspects"
      (try
        (->> [large small (assoc large :file "large2")]
             (scf-storage/handle-resource-insert-sql-ex ex-ex "foo"))
        (assert false "expected exception not thrown")
        (catch ExceptionInfo ex
          (-> ex ex-data validate-data)
          (is (= (str "A catalog resource for certname \"foo\" is too large;"
                      " suspects: {:file \"large\", :line 1337} {:file \"large2\", :line 1337}")
                 (ex-message ex))))))))

(deftest resource-key-too-big-for-pg-index
  ;; postgres restricts the index key to 8191 bytes, logging, e.g
  ;;   ERROR:  index row requires 22920 bytes, maximum size is 8191
  (with-test-db
    (add-certname! certname)
    (let [rkey #(select-keys % [:type :title])
          giant {:type "Class"
                 :title (str/join "" (.repeat "yo" 1000000))
                 :line 1337
                 :exported false
                 :file "badfile.txt"}]
      (try
        (replace-catalog!
         (assoc-in catalog [:resources (rkey giant)] giant))
        (throw (Exception. "Did not trigger program-limit-exceeded as expected"))
        (catch ExceptionInfo ex
          (is (= ::scf-storage/resource-insert-limit-exceeded (-> ex ex-data :kind)))
          (is (= "A catalog resource for certname \"basic.catalogs.com\" is too large: {:file \"badfile.txt\", :line 1337}"
                 (ex-message ex))))))))

(deftest-db catalog-persistence
  (testing "Persisted catalogs"
    (add-certname! certname)
    (replace-catalog! (assoc catalog :producer_timestamp current-time))

    (testing "should contain proper catalog metadata"
      (is (= (query-to-vec ["SELECT certname, api_version, catalog_version, producer_timestamp FROM catalogs"])
             [{:certname certname :api_version 1 :catalog_version "123456789" :producer_timestamp (to-timestamp current-time)}])))

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
        (is (= (query-to-vec ["SELECT c.certname, cr.type, cr.title
                                 FROM catalog_resources cr, certnames c
                                 WHERE c.id=cr.certname_id
                                 ORDER BY cr.type, cr.title"])
               [{:certname certname :type "Class" :title "foobar"}
                {:certname certname :type "File"  :title "/etc/foobar"}
                {:certname certname :type "File"  :title "/etc/foobar/baz"}])))

      (testing "with all parameters"
        (is (= (query-to-vec ["SELECT cr.type, cr.title, rp.name, rp.value FROM catalog_resources cr, resource_params rp WHERE rp.resource=cr.resource ORDER BY cr.type, cr.title, rp.name"])
               [{:type "File" :title "/etc/foobar" :name "ensure" :value (sutils/db-serialize "directory")}
                {:type "File" :title "/etc/foobar" :name "group" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar" :name "user" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar/baz" :name "ensure" :value (sutils/db-serialize "directory")}
                {:type "File" :title "/etc/foobar/baz" :name "group" :value (sutils/db-serialize "root")}
                {:type "File" :title "/etc/foobar/baz" :name "require" :value (sutils/db-serialize "File[/etc/foobar]")}
                {:type "File" :title "/etc/foobar/baz" :name "user" :value (sutils/db-serialize "root")}])))

      (testing "with all metadata"
        (let [result (query-to-vec ["SELECT cr.type, cr.title, cr.exported, cr.tags, cr.file, cr.line FROM catalog_resources cr ORDER BY cr.type, cr.title"])]
          (is (= (map #(assoc % :tags (sort (:tags %))) result)
                 [{:type "Class" :title "foobar" :tags ["class" "foobar"] :exported false :file nil :line nil}
                  {:type "File" :title "/etc/foobar" :tags ["class" "file" "foobar"] :exported false :file "/tmp/foo" :line 10}
                  {:type "File" :title "/etc/foobar/baz" :tags ["class" "file" "foobar"] :exported false :file "/tmp/bar" :line 20}])))))))

(deftest-db catalog-persistence-with-environment
  (let [other-certname "notbasic.catalogs.com"]
    (testing "Persisted catalogs"
      (add-certname! certname)
      (add-certname! other-certname)

      (is (nil? (environment-id "PROD")))

      (replace-catalog! (assoc catalog :environment "PROD"))

      (testing "should persist environment if the environment is new"
        (let [id (environment-id "PROD")]
          (is (number? (environment-id "PROD")))
          (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                 (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

          (testing "Adding another catalog with the same environment should just use the existing environment"
            (replace-catalog! (assoc catalog :environment "PROD" :certname other-certname))

            (is (= [{:certname other-certname :api_version 1 :catalog_version "123456789" :environment_id id}]
                   (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs where certname=?" other-certname])))))))))

(deftest-db updating-catalog-environment
  (testing "should persist environment if the environment is new"
    (let [prod-id (ensure-environment "PROD")
          dev-id (ensure-environment "DEV")]

      (add-certname! certname)
      (replace-catalog! (assoc catalog :environment "DEV"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id dev-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"])))

      (replace-catalog! (assoc catalog :environment "PROD"))
      (is (= [{:certname certname :api_version 1 :catalog_version "123456789" :environment_id prod-id}]
             (query-to-vec ["SELECT certname, api_version, catalog_version, environment_id FROM catalogs"]))))))

(deftest-db catalog-replacement
  (testing "should noop if replaced by themselves"
    (add-certname! certname)
    (let [status (replace-catalog! catalog)]
      (replace-catalog! catalog (now))

      (is (= (query-to-vec ["SELECT certname FROM certnames"])
             [{:certname certname}]))

      (is (= (query-to-vec [(format "SELECT %s AS hash FROM catalogs" (sutils/sql-hash-as-str "hash"))])
             [{:hash (:hash status)}])))))

(deftest-db edge-replacement-differential
  (testing "should do selective inserts/deletes when edges are modified just slightly"
    (add-certname! certname)
    (let [original-catalog (:basic catalogs)
          original-edges   (:edges original-catalog)
          modified-edges   (conj (disj original-edges {:source {:type "Class" :title "foobar"}
                                                       :target {:type "File" :title "/etc/foobar"}
                                                       :relationship :contains})
                                 {:source {:type "File" :title "/etc/foobar"}
                                  :target {:type "File" :title "/etc/foobar/baz"}
                                  :relationship :before})
          modified-catalog (assoc original-catalog :edges modified-edges)]
      ;; Add an initial catalog, we don't care to intercept the SQL yet
      (replace-catalog! original-catalog (now))

      (testing "ensure catalog-edges-map returns a predictable value"
        (is (= (catalog-edges-map certname)
               {["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                 "57495b553981551c5194a21b9a26554cd93db3d9"
                 "contains"] nil,
                 ["57495b553981551c5194a21b9a26554cd93db3d9"
                  "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                  "required-by"] nil,
                  ["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                   "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                   "contains"] nil})))

      ;; Lets intercept the insert/update/delete level so we can test it later
      ;; Here we only replace edges, so we can capture those specific SQL
      ;; operations
      (tu/with-wrapped-fn-args [insert-multis jdbc/insert-multi!
                                deletes jdbc/delete!]
        (let [resources    (:resources modified-catalog)
              refs-to-hash (reduce-kv (fn [i k v]
                                        (assoc i k (shash/resource-identity-hash v)))
                                      {} resources)]
          (replace-edges! certname modified-edges refs-to-hash)
          (testing "ensure catalog-edges-map returns a predictable value"
            (is (= (catalog-edges-map certname)
                   {["ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                     "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                     "contains"] nil,
                     ["57495b553981551c5194a21b9a26554cd93db3d9"
                      "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                      "required-by"] nil
                      ["57495b553981551c5194a21b9a26554cd93db3d9"
                       "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1"
                       "before"] nil})))

          (testing "should only delete the 1 edge"
            (let [source-hash "ff0702ba8a7dc69d3fb17f9d151bf9bd265a9ed9"
                  target-hash "57495b553981551c5194a21b9a26554cd93db3d9"]
              (is (= [[:edges [(str "certname=?"
                                    " and source=?::bytea"
                                    " and target=?::bytea"
                                    " and type=?")
                               "basic.catalogs.com"
                               (sutils/bytea-escape source-hash)
                               (sutils/bytea-escape target-hash)
                               "contains"]]]
                     @deletes))))
          (testing "should only insert the 1 edge"
            (is (= [[:edges [{:certname "basic.catalogs.com"
                             :source (sutils/munge-hash-for-storage "57495b553981551c5194a21b9a26554cd93db3d9")
                             :target (sutils/munge-hash-for-storage "e247f822a0f0bbbfff4fe066ce4a077f9c03cdb1")
                             :type "before"}]]]
                   @insert-multis)))
          (testing "when reran to check for idempotency"
            (reset! insert-multis [])
            (reset! deletes [])
            (replace-edges! certname modified-edges refs-to-hash)
            (testing "should delete no edges"
              (is (empty? @deletes)))
            (testing "should insert no edges"
              (is (empty? @insert-multis)))))))))

(deftest-db catalog-duplicates
  (testing "should share structure when duplicate catalogs are detected for the same host"
    (add-certname! certname)
    (let [status (replace-catalog! catalog)
          prev-dupe-num (counters/value (:duplicate-catalog @storage-metrics))
          prev-new-num  (counters/value (:updated-catalog @storage-metrics))]

      ;; Do an initial replacement with the same catalog
      (replace-catalog! catalog (now))
      (is (= 1 (- (counters/value (:duplicate-catalog @storage-metrics)) prev-dupe-num)))
      (is (= 0 (- (counters/value (:updated-catalog @storage-metrics)) prev-new-num)))

      ;; Store a second catalog, with the same content save the version
      (replace-catalog! (assoc catalog :version "abc123") (now))
      (is (= 2 (- (counters/value (:duplicate-catalog @storage-metrics)) prev-dupe-num)))
      (is (= 0 (- (counters/value (:updated-catalog @storage-metrics)) prev-new-num)))

      (is (= (query-to-vec ["SELECT certname FROM certnames"])
             [{:certname certname}]))

      (is (= (query-to-vec [(format "SELECT certname, %s AS hash FROM catalogs" (sutils/sql-hash-as-str "hash"))])
             [{:hash (:hash status)
               :certname certname}]))

      (replace-catalog! (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :line] 20) (now))
      (is (= 2 (- (counters/value (:duplicate-catalog @storage-metrics)) prev-dupe-num)))
      (is (= 1 (- (counters/value (:updated-catalog @storage-metrics)) prev-new-num))))))

(deftest-db fact-delete-deletes-facts
  (add-certname! certname)
  ;; Add some facts
  (let [facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"
               "networking" {"eth0" {"ipaddresses" ["192.168.0.11"]}}}]
    (add-facts! {:certname certname
                 :values facts
                 :timestamp (-> 2 days ago)
                 :environment "ENV3"
                 :producer_timestamp (-> 2 days ago)
                 :producer "bar.com"}))
  (is (= 6 (count-facts)))
  (is (= 6 (count-facts)))
  (delete-certname-facts! certname)
  (is (= 0 (count-facts)))
  (is (= 0 (count-facts))))

(deftest-db catalog-bad-input
  (testing "should noop"
    (testing "on bad input"
      (is (thrown? clojure.lang.ExceptionInfo (replace-catalog! {})))

      ;; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
             [{:nrows 0}])))))

(defn foobar->foobar2 [x]
  (if (and (string? x) (= x "/etc/foobar"))
    "/etc/foobar2"
    x))

(defn table-args
  "Many of the puppetdb.jdbc functions accept a table name as the first arg, this
   function grabs that argument"
  [coll]
  (map first coll))

(defn remove-edge-changes
  "Remove the edge related changes from the `coll` of function call arguments"
  [coll]
  (remove #(= :edges (first %)) coll))

(defn sort= [& args]
  (apply = (map sort args)))

(deftest-db existing-catalog-update
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)]

    (testing "inserting new catalog with resources"

      (add-certname! certname)
      (is (empty? (query-to-vec "SELECT * from catalogs where certname=?" certname)))

      (replace-catalog! catalog old-date)

      (let [results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first results)]

        (is (= 1 (count results)))
        (is (= (to-timestamp old-date) (to-timestamp timestamp)))))

    (testing "changing a resource title"
      (let [[{orig-id :id
              orig-tx-id :transaction_uuid
              orig-timestamp :timestamp}]
            (query-to-vec (str "select id, timestamp, transaction_uuid::text"
                               "  from catalogs where certname=?")
                          certname)
            updated-catalog (walk/prewalk foobar->foobar2 (:basic catalogs))
            new-uuid (kitchensink/uuid)]

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c
                                   INNER JOIN certnames on c.certname=certnames.certname
                                   INNER JOIN catalog_resources cr
                                   ON certnames.id=cr.certname_id
                                   WHERE c.certname=?" certname))))

        (tu/with-wrapped-fn-args [deletes jdbc/delete!
                                  updates jdbc/update!]

          (swap! storage-metrics
                 (fn [old-metrics]
                   (assoc old-metrics
                          :catalog-volatility (histogram storage-metrics-registry [(str (gensym))]))))

          ;; 2 edge deletes
          ;; 2 edge inserts
          ;; 1 params insert
          ;; 1 params cache insert
          ;; 1 catalog_resource insert
          ;; 1 catalog_resource delete
          (let [inserts (atom [])]
            (binding [*note-add-params-insert* #(do (swap! inserts conj %) %)
                      *note-insert-catalog-resources-insert* #(do (swap! inserts conj %) %)]
              (replace-catalog! (assoc updated-catalog :transaction_uuid new-uuid) yesterday)
              (is (= [:resource_params_cache :resource_params :catalog_resources]
                     (map :insert-into @inserts)))))

          (is (= 8 (apply + (sample (:catalog-volatility @storage-metrics)))))
          (is (= [:catalogs] (table-args @updates)))
          (is (= [[:catalog_resources ["certname_id = ? and type = ? and title = ?"
                                       (-> @updates first (nth 2) second)
                                       "File" "/etc/foobar"]]]
                 (remove-edge-changes @deletes))))

        (is (= #{{:type "Class" :title "foobar"}
                 {:type "File" :title "/etc/foobar2"}
                 {:type "File" :title "/etc/foobar/baz"}}
               (set (query-to-vec "SELECT cr.type, cr.title
                                   FROM catalogs c
                                   INNER JOIN certnames ON certnames.certname = c.certname
                                   INNER JOIN catalog_resources cr ON cr.certname_id = certnames.id
                                   WHERE c.certname=?" certname))))

        (let [results (query-to-vec
                       (str "select id, timestamp, transaction_uuid::text"
                            "  from catalogs where certname=?")
                       certname)
              {new-timestamp :timestamp
               new-tx-id :transaction_uuid
               new-id :id} (first results)]

          (is (= 1 (count results)))
          (is (= (to-timestamp yesterday) (to-timestamp new-timestamp)))
          (is (= new-tx-id new-uuid))
          (is (= orig-id new-id))
          (is (not= orig-tx-id new-tx-id))
          (is (not= orig-timestamp new-timestamp)))))))

(comment
  (existing-catalog-update)
  )

(deftest-db add-resource-to-existing-catalog
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)]
    (add-certname! certname)
    (replace-catalog! catalog old-date)

    (is (= 3 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))

    (tu/with-wrapped-fn-args [updates jdbc/update!
                              deletes jdbc/delete!]
      (let [inserts (atom [])]
        (binding [*note-add-params-insert* #(do (swap! inserts conj %) %)
                  *note-insert-catalog-resources-insert* #(do (swap! inserts conj %) %)]
          (replace-catalog! (assoc-in catalog
                                      [:resources {:type "File" :title "/etc/foobar2"}]
                                      {:type "File"
                                       :title "/etc/foobar2"
                                       :exported false
                                       :file "/tmp/foo2"
                                       :line 20
                                       :tags #{"file" "class" "foobar"}
                                       :parameters {:ensure "directory"
                                                    :group "root"
                                                    :user "root"}})
                            old-date))
        (is (= [:resource_params_cache :resource_params :catalog_resources]
               (map :insert-into @inserts))))

      (is (= [:catalogs] (table-args @updates)))
      (is (empty? @deletes)))

    (is (= 4 (:c (first (query-to-vec "SELECT count(*) AS c FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))))

(deftest-db change-line-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:line nil}
             {:line 10}
             {:line 20}}
           (set (query-to-vec "SELECT line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :line 1000) resources))))

    (is (= [{:line 1000}
            {:line 1000}
            {:line 1000}]
           (query-to-vec "SELECT line FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname)))))

(deftest-db change-exported-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing exported"
    (is (= #{{:exported false
              :title "foobar"}
             {:exported false
              :title "/etc/foobar"}
             {:exported false
              :title "/etc/foobar/baz"}}
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (-> resources
                                   (assoc-in [{:type "Class" :title "foobar"} :exported] true)
                                   (assoc-in [{:type "File" :title "/etc/foobar/baz"} :exported] true)))))
    (is (= #{{:exported true
              :title "foobar"}
             {:exported false
              :title "/etc/foobar"}
             {:exported true
              :title "/etc/foobar/baz"}}
           (set (query-to-vec "SELECT title, exported FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))))

(deftest-db change-file-resource-metadata
  (add-certname! certname)
  (replace-catalog! catalog)

  (testing "changing line number"
    (is (= #{{:title "foobar"
              :file nil}
             {:title "/etc/foobar"
              :file "/tmp/foo"}
             {:title "/etc/foobar/baz"
              :file "/tmp/bar"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))

    (replace-catalog! (update-in catalog [:resources]
                             (fn [resources]
                               (kitchensink/mapvals #(assoc % :file "/tmp/foo.pp") resources))))

    (is (= #{{:title "foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar"
              :file "/tmp/foo.pp"}
             {:title "/etc/foobar/baz"
              :file "/tmp/foo.pp"}}
           (set (query-to-vec "SELECT title, file FROM catalog_resources WHERE certname_id = (select id from certnames where certname = ?)" certname))))))

(defn tags->set
  "Converts tags from a pg-array to a set of strings"
  [result-set]
  (mapv (fn [result]
          (update-in result [:tags] #(jdbc/convert-any-sql-array % set)))
        result-set))

(deftest-db change-tags-on-resource
  (add-certname! certname)
  (replace-catalog! catalog)

  (is (= #{{:title "foobar"
            :tags #{"class" "foobar"}
            :line nil}
           {:title "/etc/foobar"
            :tags #{"file" "class" "foobar"}
            :line 10}
           {:title "/etc/foobar/baz"
            :tags #{"file" "class" "foobar"}
            :line 20}}
         (call-with-query-rows
          [(str "select title, tags, line from catalog_resources"
                "  inner join certnames c on c.id = certname_id"
                "  where c.certname = ?")
           certname]
          #(-> % tags->set set))))

  (replace-catalog! (update-in catalog [:resources]
                           (fn [resources]
                             (-> resources
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :tags] #{"totally" "different" "tags"})
                                 (assoc-in [{:type "File" :title "/etc/foobar"} :line] 500)))))
  (is (= #{{:title "foobar"
            :tags #{"class" "foobar"}
            :line nil}
           {:title "/etc/foobar"
            :line 500
            :tags #{"totally" "different" "tags"}}
           {:title "/etc/foobar/baz"
            :line 20
            :tags #{"file" "class" "foobar"}}}
         (call-with-query-rows
          [(str "select title, tags, line from catalog_resources"
                "  inner join certnames c on c.id = certname_id"
                "  where c.certname = ?")
           certname]
          #(-> % tags->set set)))))

(deftest-db removing-resources
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)
        catalog-with-extra-resource (assoc-in catalog
                                              [:resources {:type "File" :title "/etc/the-foo"}]
                                              {:type       "File"
                                               :title      "/etc/the-foo"
                                               :exported   false
                                               :file       "/tmp/the-foo"
                                               :line       10
                                               :tags       #{"file" "class" "the-foo"}
                                               :parameters {:ensure "directory"
                                                            :group  "root"
                                                            :user   "root"}})]
    (add-certname! certname)
    (replace-catalog! catalog-with-extra-resource old-date)

    (let [certname-id (:id (first (query-to-vec "SELECT id from certnames where certname=?" certname)))]
      (is (= 4 (count (query-to-vec "SELECT * from catalog_resources where certname_id = ?" certname-id))))

      (tu/with-wrapped-fn-args [inserts jdbc/insert!
                                updates jdbc/update!
                                deletes jdbc/delete!]

        (replace-catalog! catalog yesterday)
        (is (empty? @inserts))
        (is (= [:catalogs] (table-args @updates)))
        (is (= [:catalog_resources] (table-args @deletes))))

      (let [catalog-results (query-to-vec "SELECT timestamp from catalogs where certname=?" certname)
            {:keys [timestamp]} (first catalog-results)
            resources (set (query-to-vec "SELECT type, title from catalog_resources where certname_id = ?" certname-id))]

        (is (= 1 (count catalog-results)))
        (is (= 3 (count resources)))
        (is (= (set (keys (:resources catalog)))
               resources))
        (is (= (to-timestamp yesterday) (to-timestamp timestamp)))))))

(defn foobar-params []
  (jdbc/query-with-resultset
   ["SELECT p.name AS k, p.value AS v
       FROM catalog_resources cr, certnames c, resource_params p
       WHERE cr.certname_id = c.id AND cr.resource = p.resource AND c.certname=?
         AND cr.type=? AND cr.title=?"
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   (fn [rs]
     (reduce (fn [acc row]
               (assoc acc (keyword (:k row))
                      (json/parse-string (:v row))))
             {}
             (sql/result-set-seq rs)))))

(defn foobar-params-cache []
  (jdbc/query-with-resultset
   ["SELECT rpc.parameters as params
       FROM catalog_resources cr, certnames c, resource_params_cache rpc
       WHERE cr.certname_id = c.id AND cr.resource = rpc.resource AND c.certname=?
         AND cr.type=? AND cr.title=?"
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   #(-> (sql/result-set-seq %)
        first
        :params
        sutils/parse-db-json)))

(defn foobar-param-hash []
  (jdbc/query-with-resultset
   [(format "SELECT %s AS hash
               FROM catalog_resources cr, certnames c
               WHERE cr.certname_id = c.id AND c.certname=? AND cr.type=?
                 AND cr.title=?"
            (sutils/sql-hash-as-str "cr.resource"))
    (get-in catalogs [:basic :certname]) "File" "/etc/foobar"]
   (comp :hash first sql/result-set-seq)))

(deftest-db catalog-resource-parameter-changes
  (let [{certname :certname :as catalog} (:basic catalogs)
        old-date (-> 2 days ago)
        yesterday (-> 1 days ago)]
    (add-certname! certname)
    (replace-catalog! catalog old-date)

    (let [orig-resource-hash (foobar-param-hash)
          add-param-catalog (assoc-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters :uid] "100")]
      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))

      (tu/with-wrapped-fn-args [insert-multis jdbc/insert-multi!
                                updates jdbc/update!
                                deletes jdbc/delete!]

        (let [inserts (atom [])]
          (binding [*note-add-params-insert* #(do (swap! inserts conj %) %)]
            (replace-catalog! add-param-catalog yesterday)
            (is (= [:resource_params_cache :resource_params] (map :insert-into @inserts)))))

        (is (sort= [:catalogs :catalog_resources] (table-args @updates)))

        (is (empty? (remove-edge-changes @deletes)))

        (is (= [:edges]
               ;; remove inserts w/out rows
               (->> @insert-multis (remove #(empty? (second %))) table-args))))

      (is (not= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in add-param-catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache)))

      (tu/with-wrapped-fn-args [inserts jdbc/insert!
                                insert-multis jdbc/insert-multi!
                                updates jdbc/update!
                                deletes jdbc/delete!]
        (replace-catalog! catalog old-date)

        (is (empty? (remove #(or (= :edges (first %)) (empty? (second %)))
                            (concat @inserts @insert-multis))))
        (is (empty? (remove #(= :edges (first %)) @deletes)))
        (is (= (sort [:catalog_resources :catalogs])
               (sort (table-args @updates)))))

      (is (= orig-resource-hash (foobar-param-hash)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params)))

      (is (= (get-in catalog [:resources {:type "File" :title "/etc/foobar"} :parameters])
             (foobar-params-cache))))))

(deftest-db catalog-referential-integrity-violation
  (testing "on input that violates referential integrity"
    ;; This catalog has an edge that points to a non-existant resource
    (let [catalog (:invalid catalogs)]
      (is (thrown? clojure.lang.ExceptionInfo (replace-catalog! catalog)))

      ;; Nothing should have been persisted for this catalog
      (is (= (query-to-vec ["SELECT count(*) as nrows from certnames"])
             [{:nrows 0}])))))

(deftest-db have-newer-record-for-certname
  (let [certname "foo.example.com"
        now-sql (to-timestamp (now))
        two-days-from-now (-> 2 days from-now to-timestamp)]
    (testing "should not have newer record on empty db"
      (is (not (have-newer-record-for-certname? certname now-sql))))

    (testing "should identify newer record with no reports"
      ;; this tests that the function handles a null entry in certnames.latest_report_timestamp
      (add-certname! certname)
      (replace-catalog! (assoc (:basic catalogs)
                               :certname certname
                               :producer_timestamp (-> 1 days from-now)))
      (is (not (have-newer-record-for-certname? certname two-days-from-now)))
      (is (have-newer-record-for-certname? certname now-sql)))

    (testing "with all data populated"
      (store-example-report! (assoc (:basic reports) :producer_timestamp (now)) (now))
      (replace-facts! {:certname certname
                       :values {}
                       :environment "DEV"
                       :producer_timestamp (now)
                       :timestamp (now)
                       :producer "foo"})

      (is (have-newer-record-for-certname? certname now-sql))
      (is (not (have-newer-record-for-certname? certname two-days-from-now))))))

(deftest-db node-deactivation
  (let [certname        "foo.example.com"
        query-certnames #(query-to-vec ["select certname, deactivated from certnames_status"])
        deactivated?    #(instance? java.sql.Timestamp (:deactivated %))]
    (add-certname! certname)

    (testing "deactivating a node"
      (testing "should mark the node as deactivated"
        (deactivate-node! certname)
        (let [result (first (query-certnames))]
          (is (= certname (:certname result)))
          (is (deactivated? result))))

      (testing "should not change the node if it's already inactive"
        (let [original (query-certnames)]
          (deactivate-node! certname)
          ;; Convert any :deactivated values to #t for comparison
          ;; since we only care about the state.
          (letfn [(deactivated->truthy [x]
                    (assoc x :deactivated (when (:deactivated x) true)))]
            (is (= (map deactivated->truthy original)
                   (map deactivated->truthy (query-certnames))))))))

    (testing "auto-reactivated based on a command"
      (let [before-deactivating (to-timestamp (-> 1 days ago))
            after-deactivating  (to-timestamp (-> 1 days from-now))]
        (testing "should activate the node if the command happened after it was deactivated"
          (deactivate-node! certname)
          (is (= true (maybe-activate-node! certname after-deactivating)))
          (is (= (query-certnames) [{:certname certname :deactivated nil}])))

        (testing "should not activate the node if the command happened before it was deactivated"
          (deactivate-node! certname)
          (is (= false (maybe-activate-node! certname before-deactivating)))
          (let [result (first (query-certnames))]
            (is (= certname (:certname result)))
            (is (deactivated? result))))))))

(deftest-db fresh-node-not-expired
  (testing "fresh nodes are not expired"
    (let [catalog (:empty catalogs)
          certname (:certname catalog)]
      (add-certname! certname)
      (replace-catalog! (assoc catalog :producer_timestamp (now)) (now))
      (is (= [] (expire-stale-nodes (-> 3 days .toPeriod))))
      (is (= (map :certname (query-to-vec "select certname from certnames"))
             [certname])))))

(deftest expire-nodes-with-stale-catalogs-and-facts-or-none
  (testing "nodes with only stale facts/catalogs or no facts/catalogs expire"
    (let [mutators {:rc #(replace-catalog!
                          (assoc (:empty catalogs) :certname "node1")
                          (-> 2 days ago))
                    :rf #(replace-facts!
                          {:certname "node1"
                           :values {"foo" "bar"}
                           :environment "DEV"
                           :producer_timestamp (-> 10 days ago)
                           :timestamp (-> 2 days ago)
                           :producer "baz.com"})}]
      (doseq [ops (subsets (keys mutators))]
        (with-test-db
          (add-certname! "node1")
          (dorun (map #((mutators %)) ops))
          (is (= [ops ["node1"]]
                 [ops (expire-stale-nodes (-> 1 days .toPeriod))])))))))

(deftest-db node-with-only-fresh-report-is-not-expired
  (testing "does not expire a node with a recent report and nothing else"
    (let [report (-> (:basic reports)
                     (assoc :environment "ENV2")
                     (assoc :end_time (now))
                     (assoc :producer_timestamp (now)))]
      (store-example-report! report (now))
      (is (= [] (expire-stale-nodes (-> 1 days .toPeriod)))))))

(deftest stale-nodes-expiration-via-reports
  (let [report-at #(assoc (:basic reports)
                          :environment "ENV2"
                          :end_time %
                          :producer_timestamp %)
        stamp (now)
        stale-stamp-1 (-> 2 days ago)
        stale-stamp-2 (-> 3 days ago)]
    (with-test-db
      (testing "doesn't return node with a recent report and nothing else"
        (store-example-report! (report-at stamp) stamp)
        (is (= []  (expire-stale-nodes (-> 1 days .toPeriod)))))
      (testing "doesn't return node with a recent report and a stale report"
        (store-example-report! (report-at stale-stamp-1) stale-stamp-1)
        (is (= []  (expire-stale-nodes (-> 1 days .toPeriod))))))
    (with-test-db
      (testing "returns a node with only stale reports"
        (store-example-report! (report-at stale-stamp-1) stale-stamp-1)
        (store-example-report! (report-at stale-stamp-2) stale-stamp-2)
        (is (= ["foo.local"] (expire-stale-nodes (-> 1 days .toPeriod))))))))

(deftest-db stale-nodes-expiration-via-catalogs
  (let [repcat (fn [type stamp]
                 (replace-catalog! (assoc (type catalogs)
                                          :certname "node1"
                                          :producer_timestamp stamp)
                                   stamp))
        stamp (now)
        stale-stamp (-> 2 days ago)]
    (with-test-db
      (testing "doesn't return node with a recent catalog and nothing else"
        (add-certname! "node1")
        (repcat :empty stamp)
        (is (= [] (expire-stale-nodes (-> 1 days .toPeriod))))))
    (with-test-db
      (testing "returns a node with only a stale catalog"
        (add-certname! "node1")
        (repcat :empty stale-stamp)
        (is (= ["node1"] (expire-stale-nodes (-> 1 days .toPeriod))))))))

(deftest-db only-nodes-older-than-max-age-expired
  (testing "should only return nodes older than max age, and leave others alone"
    (let [catalog (:empty catalogs)]
      (add-certname! "node1")
      (add-certname! "node2")
      (replace-catalog! (assoc catalog
                               :certname "node1"
                               :producer_timestamp (-> 2 days ago))
                        (now))
      (replace-catalog! (assoc catalog
                               :certname "node2"
                               :producer_timestamp (now))
                        (now))
      (is (= ["node1"] (expire-stale-nodes (-> 1 days .toPeriod)))))))

(deftest-db node-purge
  (testing "should purge nodes which were deactivated before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (deactivate-node! "node1")
    (deactivate-node! "node2" (-> 10 days ago))
    (purge-deactivated-and-expired-nodes! (-> 5 days ago))
    (is (= (map :certname
                (query-to-vec
                 "select certname from certnames order by certname asc"))
           ["node1" "node3"]))))

(deftest-db node-purge-cleans-facts
  (let [facts {"domain" "mydomain.com"
               "fqdn" "myhost.mydomain.com"
               "hostname" "myhost"
               "kernel" "Linux"
               "operatingsystem" "Debian"}]
    (add-certname! "node1")
    (add-certname! "node2")
    (add-facts! {:certname "node1"
                 :values facts
                 :timestamp previous-time
                 :environment nil
                 :producer_timestamp previous-time
                 :producer "bar.com"})
    (add-facts! {:certname "node2"
                 :values facts
                 :timestamp previous-time
                 :environment nil
                 :producer_timestamp previous-time
                 :producer "bar.com"}))

  (deactivate-node! "node1")
  (deactivate-node! "node2" (-> 10 days ago))
  (purge-deactivated-and-expired-nodes! (-> 5 days ago))

  (let [facts (query-to-vec "select certname from factsets")]
    (is (= 1
           (count facts)))
    (is (= "node1"
           (:certname (first facts))))))

(deftest-db node-purge-cleans-catalogs
  (add-certname! "node1")
  (add-certname! "node2")
  (replace-catalog! (assoc catalog :certname "node1"
                           :producer_timestamp previous-time))
  (replace-catalog! (assoc catalog :certname "node2"
                           :producer_timestamp previous-time))

  (deactivate-node! "node1")
  (deactivate-node! "node2" (-> 10 days ago))
  (purge-deactivated-and-expired-nodes! (-> 5 days ago))

  (let [catalogs (query-to-vec "select certname from catalogs")]
    (is (= 1
           (count catalogs)))
    (is (= "node1"
           (:certname (first catalogs))))))

(deftest-db node-purge-cleans-reports
  (add-certname! "node1")
  (add-certname! "node2")
  (store-example-report! (assoc (:basic reports)
                                :certname "node1"
                                :producer_timestamp previous-time) (now))
  (store-example-report! (assoc (:basic reports)
                                :certname "node2"
                                :producer_timestamp previous-time) (now))

  (deactivate-node! "node1")
  (deactivate-node! "node2" (-> 10 days ago))
  (purge-deactivated-and-expired-nodes! (-> 5 days ago))

  (let [reports (query-to-vec
                 "select certname from reports")]
    (is (= 1
           (count reports)))
    (is (= "node1"
           (:certname (first reports))))))

(deftest-db node-purge-cleans-packages
  (testing "should purge nodes which were deactivated before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (insert-packages "node1" [["foo" "1.2.3" "apt"] ["bar" "2.3.4" "apt"]])
    (insert-packages "node2" [["foo" "1.2.3" "apt"] ["bar" "2.3.4" "apt"]])
    (deactivate-node! "node1")
    (deactivate-node! "node2" (-> 10 days ago))
    (purge-deactivated-and-expired-nodes! (-> 5 days ago))

    (is (= 1
           (count (query-to-vec
                   "select certname_id from certname_packages group by certname_id"))))))

(deftest-db delete-certname-cleans-packages
  (add-certname! "node1")
  (add-certname! "node2")
  (insert-packages "node1" [["foo" "1.2.3" "apt"] ["bar" "2.3.4" "apt"]])
  (insert-packages "node2" [["foo" "1.2.3" "apt"] ["bar" "2.3.4" "apt"]])
  (delete-certname! "node1")

  (is (= 1
         (count (query-to-vec
                 "select certname_id from certname_packages group by certname_id")))))

(deftest-db purge-expired-nodes
  (testing "should purge nodes which were expired before the specified date"
    (add-certname! "node1")
    (add-certname! "node2")
    (add-certname! "node3")
    (expire-node! "node1" (now))
    (expire-node! "node2" (-> 10 days ago))
    (purge-deactivated-and-expired-nodes! (-> 5 days ago))
    (is (= (map :certname
                (query-to-vec
                 "select certname from certnames order by certname asc"))
           ["node1" "node3"]))))

(deftest-db report-sweep-nullifies-latest-report
  (testing "ensure that if the latest report is swept, latest_report_id is updated to nil"
    (let [report1 (assoc (:basic reports) :end_time (-> 12 days ago))
          report2 (assoc (:basic reports) :certname "bar.local" :end_time (now) :producer_timestamp (now))]
      (add-certname! "foo.local")
      (add-certname! "bar.local")
      (store-example-report! report1 (-> 12 days ago))
      (store-example-report! report2 (now))
      (let [ids (map :latest_report_id (query-to-vec "select latest_report_id from certnames order by certname"))
            _ (delete-reports-older-than! {:report-ttl (-> 11 days ago)})
            ids2 (map :latest_report_id (query-to-vec "select latest_report_id from certnames order by certname"))]
        (is (= ids2 [(first ids) nil]))))))

(deftest-db plan-report-does-not-update-latest-report-id
  (testing "A submission of a plan report should not update latest_report_id"
    (let [agent-report (assoc (:basic reports)
                              :end_time (-> 1 days ago))
          plan-report (assoc (:basic reports)
                             :end_time (now)
                             :producer_timestamp (now)
                             :type "plan")]
      (add-certname! "foo.local")
      (store-example-report! agent-report (now))
      (let [latest_report_id (query-to-vec "select latest_report_id from certnames")]
        (store-example-report! plan-report (now))
        (is (= latest_report_id (query-to-vec "select latest_report_id from certnames")))))))

;; Report tests

(defn update-event-timestamps
  "Changes each timestamp in the `report`'s resource_events to `new-timestamp`"
  [report new-timestamp]
  (update-in report [:resource_events]
             (fn [events]
               (map #(assoc % :timestamp new-timestamp) events))))

(let [timestamp (now)
      {:keys [certname] :as report} (:basic reports)
      report-hash (-> report
                      report/report-query->wire-v8
                      normalize-report
                      shash/report-identity-hash)]

  (deftest-db report-storage
    (testing "should store reports"
      (store-example-report! report timestamp)

      (is (= [{:certname certname}]
             (query-to-vec ["SELECT certname FROM reports"])))

      (is (= [{:hash report-hash}]
             (query-to-vec [(format "SELECT %s AS hash FROM reports" (sutils/sql-hash-as-str "hash"))])))

      (testing "foss doesn't store in the resources column"
        (is (nil? (:resources (first (query-to-vec ["SELECT resources FROM reports"])))))))

    (testing "should store report with long puppet version string"
      (store-example-report!
       (assoc report
              :puppet_version "3.2.1 (Puppet Enterprise 3.0.0-preview0-168-g32c839e)") timestamp)))

  (deftest-db report-storage-with-no-partitions
    ;; The report partition PR will create 8 default partitions
    ;; The timestamp (now) above will map to one of those paritions
    ;; so we delete all the partitions to force the storage code
    ;; to catch the error and create the partition on-demand
    (jdbc/do-commands
      "BEGIN TRANSACTION"
      "UPDATE certnames SET latest_report_id = NULL"
      "DO $$ DECLARE
           r RECORD;
       BEGIN
           FOR r IN (SELECT tablename FROM pg_tables WHERE tablename LIKE 'resource_events_%' OR tablename LIKE 'reports_%') LOOP
               EXECUTE 'DROP TABLE ' || quote_ident(r.tablename);
           END LOOP;
       END $$;"
      "COMMIT TRANSACTION")
    (store-example-report! report timestamp)
    (is (= [{:certname certname}]
           (query-to-vec ["SELECT certname FROM reports"])))

    (testing "Index is created in on demand partitions"
      (let [assert-index-exists (fn [index indexes]
                                  (is (true? (some #(str/includes? % index) indexes))))

            partitions (get-partition-names "reports")]
        ;; check that primary key index is present in on demand paritions
        ;; XXX Do we still care about this?
        (is (= 1 (count partitions)))
        (doseq [partition-name partitions
                indices (map tu/table-indexes partitions)]
           (assert-index-exists (format "%s_pkey" partition-name) indices)))))

  (deftest-db report-with-event-timestamp
    (let [z-report (update-event-timestamps report "2011-01-01T12:00:01Z")
          offset-report (update-event-timestamps report "2011-01-01T12:00:01-0000")]
      (is (= (shash/report-identity-hash (normalize-report z-report))
             (shash/report-identity-hash (normalize-report offset-report))))))

  (deftest-db report-with-null-bytes-in-events
    (store-example-report!
     (-> report
         (assoc-in [:resource_events :data 0 :new_value] "foo\u0000bar")
         (assoc-in [:resource_events :data 0 :old_value] "foo\u0000bar"))
     timestamp)

    (is (= [{:old_value "\"foo\ufffdbar\""
             :new_value "\"foo\ufffdbar\""}]
           (query-to-vec ["SELECT old_value, new_value from resource_events where old_value ~ 'foo'"]))))

  (deftest-db report-storage-with-environment
    (is (nil? (environment-id "DEV")))

    (store-example-report! (assoc report :environment "DEV") timestamp)

    (is (number? (environment-id "DEV")))

    (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
           [{:certname (:certname report)
             :environment_id (environment-id "DEV")}])))

  (deftest-db report-storage-with-producer
    (let [prod-id (ensure-producer "bar.com")]
      (store-example-report! (assoc report :producer "bar.com") timestamp)

      (is (= (query-to-vec ["SELECT certname, producer_id FROM reports"])
             [{:certname (:certname report)
               :producer_id prod-id}]))))

  (deftest-db report-storage-with-status
    (is (nil? (status-id "unchanged")))

    (store-example-report! (assoc report :status "unchanged") timestamp)

    (is (number? (status-id "unchanged")))

    (is (= (query-to-vec ["SELECT certname, status_id FROM reports"])
           [{:certname (:certname report)
             :status_id (status-id "unchanged")}])))

  (deftest-db report-storage-without-resources
    (testing "should store reports"
      (ensure-environment "DEV")
      (store-example-report! (assoc-in report [:resource_events :data] []) timestamp)
      (is (= (query-to-vec ["SELECT certname FROM reports"])
             [{:certname (:certname report)}]))
      (is (= (query-to-vec ["SELECT COUNT(1) as num_resource_events FROM resource_events"])
             [{:num_resource_events 0}]))))

  (deftest-db report-storage-with-existing-environment
    (testing "should store reports"
      (let [env-id (ensure-environment "DEV")]

        (store-example-report! (assoc report :environment "DEV") timestamp)

        (is (= (query-to-vec ["SELECT certname, environment_id FROM reports"])
               [{:certname (:certname report)
                 :environment_id env-id}])))))

  (deftest-db latest-report
    (let [node (:certname report)
          report-hash (:hash (store-example-report! report timestamp))]
      (testing "should flag report as 'latest'"
        (is (is-latest-report? node report-hash))
        (let [new-report-hash (:hash (store-example-report!
                                      (-> report
                                          (assoc :configuration_version "bar")
                                          (assoc :end_time (now))
                                          (assoc :producer_timestamp (now)))
                                      timestamp))]
          (is (is-latest-report? node new-report-hash))
          (is (not (is-latest-report? node report-hash)))))
      (testing "should not update latest report with older report timestamp"
        (let [old-report-hash (:hash (store-example-report!
                                      (-> report
                                          (assoc :configuration_version "bar")
                                          (assoc :end_time (now))
                                          (assoc :producer_timestamp (-> -1 days from-now)))
                                      timestamp))]
          (is (not (is-latest-report? node old-report-hash)))))))


  (deftest-db report-cleanup
    (testing "should delete reports older than the specified age"
      (let [report1 (assoc report
                           :certname "foo"
                           :end_time (to-string (-> 5 days ago))
                           :producer_timestamp (to-string (-> 5 days ago)))
            report2 (assoc report
                           :certname "bar"
                           :end_time (to-string (-> 2 days ago))
                           :producer_timestamp (to-string (-> 2 days ago)))]

        (store-example-report! report1 timestamp)
        (store-example-report! report2 timestamp)
        (delete-reports-older-than! {:report-ttl (-> 3 days ago)})

        (is (= (query-to-vec ["SELECT certname FROM reports"])
               [{:certname "bar"}])))))

  (deftest-db resource-events-cleanup
    (testing "should delete all events for reports older than the specified age"
      (let [report1 (assoc report :end_time (to-string (-> 5 days ago)))
            report1-hash (:hash (store-example-report! report1 timestamp))
            report2 (assoc report :end_time (to-string (-> 2 days ago)))]

        (store-example-report! report2 timestamp)
        (delete-reports-older-than! {:report-ttl (-> 3 days ago)})
        (is (= #{}
               (set (query-resource-events :latest ["=" "report" report1-hash] {})))))))

  (deftest-db report-with-no-events
              (let [node (:certname report)
                    stored-report (store-example-report!
                                    (-> report
                                        (dissoc :resource_events :events)
                                        (assoc :producer_timestamp timestamp
                                               :start_time timestamp
                                               :end_time timestamp))
                                    timestamp)
                    report-hash (:hash stored-report)]
                (testing "node is visible after store with no events"
                  (is (= {:deactivated nil
                          :latest_report_hash report-hash
                          :facts_environment nil
                          :cached_catalog_status "not_used"
                          :report_environment "DEV"
                          :latest_report_corrective_change nil
                          :catalog_environment nil
                          :facts_timestamp nil
                          :latest_report_noop false
                          :expired nil
                          :latest_report_noop_pending true
                          :report_timestamp (to-timestamp timestamp)
                          :certname node
                          :catalog_timestamp nil
                          :latest_report_job_id nil
                          :latest_report_status "unchanged"}
                         (node-for-certname :v4 node))))))

  (deftest-db report-storage-with-report-type
    (let [agent-certname "agent-certname"
          plan-certname "plan-certname"
          defaulted-certname "defaulted-cername"
          report-type-query (fn [cname]
                              (query-to-vec
                               (format "SELECT report_type FROM reports
                                        WHERE certname = '%s'" cname)))]

      (testing "reports with missing report_type should default to agent"
        (store-example-report! (assoc report :certname defaulted-certname) timestamp)
        (is (= [{:report_type "agent"}] (report-type-query defaulted-certname))))

      (testing "should store reports with report_type set to 'agent' or 'plan'"
        (store-example-report! (-> report
                                   (assoc :certname agent-certname)
                                   (assoc :type "agent")) timestamp)
        (is (= [{:report_type "agent"}] (report-type-query agent-certname)))

        (store-example-report! (-> report
                                   (assoc :certname plan-certname)
                                   (assoc :type "plan"))
                               timestamp)
        (is (= [{:report_type "plan"}] (report-type-query plan-certname))))

      (testing "invalid report_type value throws"
        (is (thrown? clojure.lang.ExceptionInfo
                     (store-example-report! (-> report
                                                (assoc :certname "boom")
                                                (assoc :type "not-valid"))
                                            timestamp)))))))

(deftest test-catalog-schemas
  (is (= (:basic catalogs) (s/validate catalog-schema (:basic catalogs)))))

(deftest test-resource-metadata-diff
  (are [expected left right] (= expected (basic-diff left right))
       {}
       {:type "foo" :title "bar"}
       {:type "foo" :title "bar"}

       {:line       20}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       10
        :tags       #{"file" "class" "foobar"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       20
        :tags       #{"file" "class" "foobar"}}

       {:exported   true
        :file       "/tmp/bar/baz"
        :line       30
        :tags       #{"file" "class" "foobar" "baz"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   false
        :file       "/tmp/bar"
        :line       20
        :tags       #{"file" "class" "foobar"}}
       {:type       "File"
        :title      "/etc/foobar/baz"
        :exported   true
        :file       "/tmp/bar/baz"
        :line       30
        :tags       #{"file" "class" "foobar" "baz"}}))

(deftest test-diff-resources-metadata
  (let [resources-1 {{:type "File" :title "/etc/foobar"}
                     {:type       "File"
                      :title      "/etc/foobar"
                      :exported   false
                      :file       "/tmp/foo"
                      :line       10
                      :tags       #{"file" "class" "foobar"}}

                     {:type "File" :title "/etc/foobar/baz"}
                     {:type       "File"
                      :title      "/etc/foobar/baz"
                      :exported   false
                      :file       "/tmp/bar"
                      :line       20
                      :tags       #{"file" "class" "foobar"}}}
        resources-2 {{:type "File" :title "/etc/foobar"}
                     {:type       "File"
                      :title      "/etc/foobar"
                      :exported   false
                      :file       "/tmp/foo"
                      :line       20
                      :tags       #{"file" "class" "foobar"}}

                     {:type "File" :title "/etc/foobar/baz"}
                     {:type       "File"
                      :title      "/etc/foobar/baz"
                      :exported   true
                      :file       "/tmp/bar/baz"
                      :line       30
                      :tags       #{"file" "class" "foobar" "baz"}}}]

    (are [expected left right] (= expected (diff-resources-metadata left right "debugging-certname"))

         {}
         resources-1
         resources-1

         {{:type "File" :title "/etc/foobar"}
          {:line 30}}
         resources-1
         (assoc-in resources-1 [{:type "File" :title "/etc/foobar"} :line] 30)

         {{:type "File" :title "/etc/foobar"}
          {:line 20}

          {:type "File" :title "/etc/foobar/baz"}
          {:exported   true
           :file       "/tmp/bar/baz"
           :line       30
           :tags       #{"file" "class" "foobar" "baz"}}}
         resources-1
         resources-2)))

(defn fake-hash
  []
  (shash/generic-identity-hash (random/random-string)))

(deftest-db giant-resources-exist
  (testing "resources-exist?"
    (is (= #{} (resources-exist? (set (take 40000 (repeatedly fake-hash))))))))

(deftest-db test-merge-resource-hash
  (let [ref->resource {{:type "File" :title "/tmp/foo"}
                       {:line 10}

                       {:type "File" :title "/tmp/bar"}
                       {:line 20}}
        ref->hash {{:type "File" :title "/tmp/foo"}
                   "foo hash"

                   {:type "File" :title "/tmp/bar"}
                   "bar hash"}]
    (is (= {{:type "File" :title "/tmp/foo"}
            {:line 10 :resource "foo hash"}

            {:type "File" :title "/tmp/bar"}
            {:line 20 :resource "bar hash"}}

           (merge-resource-hash ref->hash ref->resource)))))

(deftest-db test-resources-exist?
  (testing "With empty input"
    (is (= #{} (resources-exist? #{})))))

(deftest-db setting-fact-expiration-for-certname
  (is (= [] (query-to-vec "select * from certname_fact_expiration")))
  (add-certname! "foo")
  (is (= [] (query-to-vec "select * from certname_fact_expiration")))
  (let [stamp-1 (to-timestamp (now))
        stamp-2 (to-timestamp (time/plus (now) (time/seconds 1)))
        id (-> "select id from certnames where certname = 'foo'"
               query-to-vec first :id)]
    (set-certname-facts-expiration "foo" true stamp-1)
    (is (= [{:certid id :expire true :updated stamp-1}]
           (query-to-vec "select * from certname_fact_expiration")))
    ;; No effect if time is <=
    (set-certname-facts-expiration "foo" false stamp-1)
    (is (= [{:certid id :expire true :updated stamp-1}]
           (query-to-vec "select * from certname_fact_expiration")))
    ;; Changes for newer time
    (set-certname-facts-expiration "foo" false stamp-2)
    (is (= [{:certid id :expire false :updated stamp-2}]
           (query-to-vec "select * from certname_fact_expiration")))))

(deftest-db adding-catalog-inputs-for-certname
  (is (= [] (query-to-vec "select * from catalog_inputs")))
  (let [stamp-1 (to-timestamp (now))
        stamp-2 (to-timestamp (time/plus (now) (time/seconds 1)))
        id (-> (add-certname! "foo") first :id)]
    (replace-catalog! (assoc catalog :producer_timestamp stamp-1 :certname "foo"))
    (is (= [] (query-to-vec "select * from catalog_inputs")))
    (is (= [{:id id :certname "foo" :catalog_inputs_timestamp nil :catalog_inputs_uuid nil}]
           (query-to-vec "select certname, id, catalog_inputs_timestamp, catalog_inputs_uuid::text from certnames")))

    (replace-catalog-inputs! "foo"
                             (:catalog_uuid catalog)
                             [["hiera", "puppetdb::globals::version"]]
                             stamp-1)
    (is (= [{:certname_id 1 :type "hiera" :name "puppetdb::globals::version"}]
           (query-to-vec "select * from catalog_inputs")))
    (is (= [{:id id :certname "foo" :catalog_inputs_timestamp stamp-1 :catalog_inputs_uuid (:catalog_uuid catalog)}]
           (query-to-vec "select certname, id, catalog_inputs_timestamp, catalog_inputs_uuid::text from certnames")))

    ;; Changes for newer time, removes old inputs, supports multiple inputs
    (replace-catalog-inputs! "foo"
                             (:catalog_uuid catalog)
                             [["hiera", "puppetdb::disable_ssl"]
                              ["hiera", "puppetdb::disable_cleartext"]]
                             stamp-2)
    (is (= [{:certname_id 1 :type "hiera" :name "puppetdb::disable_cleartext"}
            {:certname_id 1 :type "hiera" :name "puppetdb::disable_ssl"}]
           (query-to-vec "select * from catalog_inputs")))
    (is (= [{:id id :certname "foo" :catalog_inputs_timestamp stamp-2 :catalog_inputs_uuid (:catalog_uuid catalog)}]
           (query-to-vec "select certname, id, catalog_inputs_timestamp, catalog_inputs_uuid::text from certnames")))

    ;; No effect if time is <=
    (replace-catalog-inputs! "foo"
                             (:catalog_uuid catalog)
                             [["hiera", "puppetdb::globals::version"]]
                             stamp-1)
    (is (= [{:certname_id 1 :type "hiera" :name "puppetdb::disable_cleartext"}
            {:certname_id 1 :type "hiera" :name "puppetdb::disable_ssl"}]
           (query-to-vec "select * from catalog_inputs")))
    (is (= [{:id id :certname "foo" :catalog_inputs_timestamp stamp-2 :catalog_inputs_uuid (:catalog_uuid catalog)}]
           (query-to-vec "select certname, id, catalog_inputs_timestamp, catalog_inputs_uuid::text from certnames")))))

(defn get-lock-timeout []
  (-> "select setting from pg_settings where name = 'lock_timeout'"
      query-to-vec first :setting Long/parseLong))

(deftest-db call-with-lock-timeout-behavior
  (let [orig (get-lock-timeout)]
    (jdbc/with-db-transaction []
      ;; Ensure an existing (in transaction (or global)) non-zero
      ;; timeout doesn't cause an exception.  Previously, we were
      ;; relying on "show lock_timeout", which produces human readable
      ;; values like 300ms rather than the integer milliseconds value
      ;; we expect to parse.
      (sql/execute! jdbc/*db* "set local lock_timeout = 300")
      (is (= 1000 (call-with-lock-timeout get-lock-timeout 1000))))
    (is (= orig (get-lock-timeout)))))

(deftest lock-ordering
  (with-redefs [jdbc/do-commands vector]
    (let [lock-stmts (acquire-locks! {"reports" "EXCLUSIVE" "resource_events" "SHARE" "certnames" "ACCESS SHARE"})]
      (is (= ["LOCK TABLE certnames IN ACCESS SHARE MODE"
              "LOCK TABLE reports IN EXCLUSIVE MODE"
              "LOCK TABLE resource_events IN SHARE MODE"]
             lock-stmts)))
    (let [lock-stmts (acquire-locks! {"reports" "EXCLUSIVE" "catalogs" "SHARE" "certnames" "ACCESS SHARE"})]
      (is (= ["LOCK TABLE catalogs IN SHARE MODE"
              "LOCK TABLE certnames IN ACCESS SHARE MODE"
              "LOCK TABLE reports IN EXCLUSIVE MODE"]
             lock-stmts)))))
