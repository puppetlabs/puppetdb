(ns puppetlabs.pe-puppetdb-extensions.reports-test
  (:require [clojure.test :refer :all]
            [clj-time.core :refer [now]]
            [clojure.string :as str]
            [puppetlabs.puppetdb.testutils.services :refer [get-json]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [with-puppetdb-instance blocking-command-post]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.reports :as reports]
            [puppetlabs.puppetdb.testutils.log
             :refer [with-log-suppressed-unless-notable notable-pdb-event?]]))

(deftest query-resources-on-reports
  (with-log-suppressed-unless-notable notable-pdb-event?
    (with-puppetdb-instance (utils/pdb1-sync-config)
      (let [report (:basic reports)]
        (->> report
             reports/report-query->wire-v6
             (blocking-command-post (utils/pdb-cmd-url) "store report" 6))
        (let [expected (->> report
                            reports/report-query->wire-v6
                            :resources
                            keywordize-keys)
              reports-response (first (get-json (utils/pdb-query-url) "/reports"))]

          (testing "resources are the same as from the reports endpoint"
            (is (= (set expected)
                   (set (->> reports-response :resources :data)))))

          (testing "resources are the same as from the child endpoint"
            (is (= (set expected)
                   (set (get-json (utils/pdb-query-url)
                                  (format "/reports/%s/resources" (-> reports-response
                                                                      :resources
                                                                      :href
                                                                      (str/split #"/")
                                                                      (nth 5)))))))))))))
