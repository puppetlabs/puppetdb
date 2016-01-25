(ns puppetlabs.pe-puppetdb-extensions.reports-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.puppetdb.testutils.services :refer [get-json]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.reports :as reports]))

(deftest query-resources-on-reports
  (with-ext-instances [pdb (utils/sync-config nil)]
      (let [report (:basic reports)
            certname (:certname report)]
        (->> report
             reports/report-query->wire-v6
             (blocking-command-post (utils/pdb-cmd-url) certname "store report" 6))
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
                                                                      (nth 5))))))))))))
