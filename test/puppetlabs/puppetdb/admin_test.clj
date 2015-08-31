(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.http.command :refer :all]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.tar :as tar]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.examples.reports :as example-reports]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.cli :refer [get-catalog get-report get-facts]]
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

         (export/export! export-out-file query-fn))))

    (svc-utils/call-with-single-quiet-pdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes export/query-api-version nil nil doall)))

         (let [command-versions (:command_versions (import/parse-metadata export-out-file))]
           (import/import! export-out-file command-versions submit-command-fn))

         @(tu/block-until-results 100 (get-catalog query-fn certname))
         @(tu/block-until-results 100 (get-report query-fn certname))
         @(tu/block-until-results 100 (get-facts query-fn certname))

         (is (tu/=-after? tuc/munge-catalog catalog (get-catalog query-fn certname)))
         (is (tu/=-after? tur/munge-report report (get-report query-fn certname)))
         (is (tu/=-after? tuf/munge-facts facts (get-facts query-fn certname))))))))
