(ns puppetlabs.pe-puppetdb-extensions.reports-test
  (:require [clojure.test :refer :all]
            [clj-time.core :refer [now]]
            [puppetlabs.puppetdb.testutils.services :as svcs]
            [puppetlabs.puppetdb.scf.storage-utils :as sutils]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance blocking-command-post]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]))

(defn get-munged-report [report]
  (tur/munge-example-report-for-storage (get reports report)))

(deftest query-resources-on-reports
  (with-log-suppressed-unless-notable notable-pdb-event?
    (with-puppetdb-instance (utils/pdb1-sync-config)
      (let [report (get-munged-report :basic)]
        (blocking-command-post (utils/pdb-cmd-url) "store report" 5 report)
        (let [expected (->> report reports/wire-v5->wire-v6 :resources clojure.walk/keywordize-keys)
              actual (->> (svcs/get-json (utils/pdb-query-url) "/reports") first :resources :data)]
          (is (= expected actual)))))))
