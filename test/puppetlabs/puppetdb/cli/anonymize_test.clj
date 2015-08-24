(ns puppetlabs.puppetdb.cli.anonymize-test
  (:require [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.cli.anonymize :as anonymize]
            [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
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
            [clj-time.core :refer [now]]
            [clj-time.coerce :refer [to-string]]
            [puppetlabs.puppetdb.utils :as utils]
            [puppetlabs.puppetdb.client :as pdb-client]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.puppetdb.testutils.services :as svc-utils]))

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
                   tur/munge-example-report-for-storage)
        certname-query ["=" "certname" certname]]

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))
             command-base-url (tu/command-base-url svc-utils/*base-url*)]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (svc-utils/sync-command-post command-base-url "replace catalog" 6 catalog)
         (svc-utils/sync-command-post command-base-url "store report" 5 report)
         (svc-utils/sync-command-post command-base-url "replace facts" 4 facts)

         (is (= (tuc/munge-catalog catalog)
                (tuc/munge-catalog (vec (export/catalogs-for-query query-fn certname-query)))))

         (is (= (tuf/munge-facts facts)
                (tuf/munge-facts (vec (export/facts-for-query query-fn certname-query)))))

         (is (= (tur/munge-report report)
                (tur/munge-report (vec (export/reports-for-query query-fn certname-query)))))

         (#'export/-main "--outfile" export-out-file-path
                         "--host" (:host svc-utils/*base-url*)
                         "--port" (str (:port svc-utils/*base-url*)))

         (doseq [profile ["full" "moderate" "none"]]
           (let [anon-out-file-path (str (.getPath export-out-dir) "/anon-" profile "-file.tar.gz")]
             (#'export/-main "--outfile" anon-out-file-path
                             "--anonymization" "full"
                             "--host" (:host svc-utils/*base-url*)
                             "--port" (str (:port svc-utils/*base-url*)))
             (is (not= (tar/archive->map export-out-file-path)
                       (tar/archive->map anon-out-file-path))))))))))
