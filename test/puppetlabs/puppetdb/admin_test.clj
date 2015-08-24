(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.http.command :refer :all]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.examples.reports :as example-reports]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [clj-time.core :as time]
            [clj-time.coerce :as time-coerce]
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
               :producer_timestamp (time-coerce/to-string (time/now))}
        export-out-file (tu/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in examples/wire-catalogs [6 :empty])
                    (assoc :certname certname
                           :producer_timestamp (time/now)))
        report (-> (:basic example-reports/reports)
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

         (is (= (tur/munge-report report)
                (tur/munge-report (vec (export/reports-for-query query-fn certname-query)))))

         (is (= (tuf/munge-facts facts)
                (tuf/munge-facts (vec (export/facts-for-query query-fn certname-query)))))

         (admin/export-app export-out-file query-fn nil))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))
             command-versions (:command_versions (import/parse-metadata export-out-file))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (import/import! export-out-file command-versions submit-command-fn)

         ;; For some reason, although the fact's/report's message has
         ;; been consumed and committed, it's not immediately available
         ;; for querying. Maybe this is a race condition in our tests?
         ;; The next two lines ensure that the message is not only
         ;; consumed but present in the DB before proceeding
         @(tu/block-until-results 100 (seq (export/catalogs-for-query query-fn nil)))
         @(tu/block-until-results 100 (seq (export/facts-for-query query-fn nil)))
         @(tu/block-until-results 100 (seq (export/reports-for-query query-fn nil)))

         (is (= (tuc/munge-catalog catalog)
                (tuc/munge-catalog (vec (export/catalogs-for-query query-fn certname-query)))))

         (is (= (tuf/munge-facts facts)
                (tuf/munge-facts (vec (export/facts-for-query query-fn certname-query)))))

         (is (= (tur/munge-report report)
                (tur/munge-report (vec (export/reports-for-query query-fn certname-query))))))))))
