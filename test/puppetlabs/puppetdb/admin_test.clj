(ns puppetlabs.puppetdb.admin-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.cli.import-export-roundtrip-test :as roundtrip]
            [puppetlabs.puppetdb.cli.services :refer :all]
            [puppetlabs.puppetdb.http.command :refer :all]
            [puppetlabs.puppetdb.cli.export :as export]
            [puppetlabs.puppetdb.cli.import :as import]
            [puppetlabs.puppetdb.admin :as admin]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.examples :as examples]
            [puppetlabs.puppetdb.examples.reports :as example-reports]
            [puppetlabs.puppetdb.testutils.reports :as tur]
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
        export-out-file (testutils/temp-file "export-test" ".tar.gz")
        catalog (-> (get-in examples/wire-catalogs [6 :empty])
                    (assoc :certname certname
                           :producer_timestamp (time/now)))
        report (-> (:basic example-reports/reports)
                   (assoc :certname certname))]

    (svc-utils/puppetdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (svc-utils/sync-command-post (roundtrip/command-base-url svc-utils/*base-url*) "replace catalog" 6 catalog)
         (svc-utils/sync-command-post (roundtrip/command-base-url svc-utils/*base-url*) "store report" 5
                                      (tur/munge-example-report-for-storage report))
         (svc-utils/sync-command-post (roundtrip/command-base-url svc-utils/*base-url*) "replace facts" 4 facts)

         (is (testutils/=-after? roundtrip/munge-catalog
                                 catalog
                                 (->> certname
                                      (export/catalogs-for-node query-fn)
                                      roundtrip/parse-tar-entry-contents)))

         (is (testutils/=-after? roundtrip/munge-report
                                 report
                                 (->> certname
                                      (export/reports-for-node query-fn)
                                      roundtrip/parse-tar-entry-contents)))
         (is (= facts (->> certname
                           (export/facts-for-node query-fn)
                           roundtrip/parse-tar-entry-contents)))

         (admin/export-app export-out-file query-fn))))

    (svc-utils/puppetdb-instance
     (fn []
       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))
             submit-command-fn (partial submit-command (tk-app/get-service svc-utils/*server* :PuppetDBCommand))]
         (is (empty? (query-fn :nodes admin/query-api-version nil nil doall)))

         (svc-utils/until-consumed
          3
          (fn []
            (let [command-versions (:command_versions (import/parse-metadata export-out-file))]
              (import/import! export-out-file command-versions submit-command-fn))))

         ;; For some reason, although the fact's/report's message has
         ;; been consumed and committed, it's not immediately available
         ;; for querying. Maybe this is a race condition in our tests?
         ;; The next two lines ensure that the message is not only
         ;; consumed but present in the DB before proceeding
         @(roundtrip/block-until-results 100 (export/catalogs-for-node query-fn certname))
         @(roundtrip/block-until-results 100 (export/facts-for-node query-fn certname))
         @(roundtrip/block-until-results 100 (export/reports-for-node query-fn certname))

         (is (testutils/=-after? roundtrip/munge-catalog
                                 catalog
                                 (->> certname
                                      (export/catalogs-for-node query-fn)
                                      roundtrip/parse-tar-entry-contents)))


         (is (= facts (->> certname
                           (export/facts-for-node query-fn)
                           roundtrip/parse-tar-entry-contents)))

         (is (testutils/=-after? roundtrip/munge-report
                                 report
                                 (->> certname
                                      (export/reports-for-node query-fn)
                                      roundtrip/parse-tar-entry-contents))))))))
