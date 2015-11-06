(ns puppetlabs.pe-puppetdb-extensions.reports-test
  (:require [clojure.test :refer :all]
            [puppetlabs.puppetdb.testutils.services :refer [get-json]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.reports :as reports]))

(deftest query-resources-on-reports
  (with-ext-instances [pdb (utils/sync-config nil)]
    (let [report (:basic reports)]
      (->> report
           reports/report-query->wire-v6
           (blocking-command-post (utils/pdb-cmd-url) "store report" 6))
      (let [expected (->> report reports/report-query->wire-v6
                          :resources keywordize-keys)
            actual (->> (get-json (utils/pdb-query-url) "/reports")
                        first :resources :data)]
        (is (= expected actual))))))
