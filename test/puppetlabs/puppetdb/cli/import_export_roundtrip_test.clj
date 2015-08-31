(ns puppetlabs.puppetdb.cli.import-export-roundtrip-test
  (:require [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.cli.export :as cli-export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.cli.anonymize :as anonymize]
            [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.http.command :refer :all]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.archive :as archive]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.cli :refer [get-catalog get-report get-facts]]
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.client :as pdb-client]
            [slingshot.slingshot :refer [throw+ try+]]
            [slingshot.test]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

(use-fixtures :each fixt/with-test-logging-silenced)

(deftest test-basic-roundtrip
  (let [certname "foo.local"
        facts {:certname certname
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
               :producer_timestamp (to-string (now))}
        export-out-dir (tu/temp-dir "export-test")
        export-out-file-path (str (.getPath export-out-dir) "/outfile.tar.gz")
        catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname certname
                           :producer_timestamp (now)))
        report (-> (:basic reports)
                   (assoc :certname certname)
                   tur/munge-example-report-for-storage)]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))
             base-url (tu/command-base-url svc-utils/*base-url*)]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))

         (svc-utils/sync-command-post base-url "replace catalog" 6 catalog)
         (svc-utils/sync-command-post base-url "store report" 5 report)
         (svc-utils/sync-command-post base-url "replace facts" 4 facts)

         (is (tu/=-after? tuc/munge-catalog catalog (get-catalog query-fn certname)))
         (is (tu/=-after? tur/munge-report report (get-report query-fn certname)))
         (is (tu/=-after? tuf/munge-facts facts (get-facts query-fn certname)))

         (#'cli-export/-main "--outfile" export-out-file-path
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*))))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))

         (#'import/-main "--infile" export-out-file-path
                         "--host" (:host svc-utils/*base-url*)
                         "--port" (str (:port svc-utils/*base-url*)))

         @(tu/block-until-results 100 (get-catalog query-fn certname))
         @(tu/block-until-results 100 (get-report query-fn certname))
         @(tu/block-until-results 100 (get-facts query-fn certname))

         (is (tu/=-after? tuc/munge-catalog catalog (get-catalog query-fn certname)))
         (is (tu/=-after? tur/munge-report report (get-report query-fn certname)))
         (is (tu/=-after? tuf/munge-facts facts (get-facts query-fn certname))))))))

(deftest test-facts-only-roundtrip
  (let [certname "foo.local"
        facts {:certname certname
               :environment "DEV"
               :values {:foo "the foo"
                        :bar "the bar"
                        :baz "the baz"
                        :biz {:a [3.14 2.71] :b "the b" :c [1 2 3] :d {:e nil}}}
               :producer_timestamp (to-string (now))}
        export-out-dir (tu/temp-dir "export-test")
        export-out-file-path (str (.getPath export-out-dir) "/outfile.tar.gz")]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))
             base-url (tu/command-base-url svc-utils/*base-url*)]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))

         (svc-utils/sync-command-post base-url "replace facts" 4 facts)

         (is (nil? (get-catalog query-fn certname)))
         (is (nil? (get-report query-fn certname)))
         (is (tu/=-after? tuf/munge-facts facts (get-facts query-fn certname)))

         (#'cli-export/-main "--outfile" export-out-file-path
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*))))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))

         (#'import/-main "--infile" export-out-file-path
                         "--host" (:host svc-utils/*base-url*)
                         "--port" (str (:port svc-utils/*base-url*)))

         @(tu/block-until-results 100 (get-facts query-fn certname))

         (is (nil? (get-catalog query-fn certname)))
         (is (nil? (get-report query-fn certname)))
         (is (tu/=-after? tuf/munge-facts facts (get-facts query-fn certname))))))))

(deftest test-max-frame-size
  (let [certname "foo.local"
        catalog (-> (get-in wire-catalogs [6 :empty])
                    (assoc :certname certname))]
    (svc-utils/call-with-single-quiet-pdb-instance
     (assoc-in (svc-utils/create-config) [:command-processing :max-frame-size] "1024")
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))
         (pdb-client/submit-command-via-http!
           (tu/command-base-url svc-utils/*base-url*)
           "replace catalog" 6 catalog)
         (is (thrown-with-msg?
              java.util.concurrent.ExecutionException #"Results not found"
              @(tu/block-until-results
                5 (first
                   (export/get-wireformatted-entity) query-fn :catalogs ["=" "certname" certname])))))))))
