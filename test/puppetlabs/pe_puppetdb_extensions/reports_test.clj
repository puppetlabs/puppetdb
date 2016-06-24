(ns puppetlabs.pe-puppetdb-extensions.reports-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [puppetlabs.puppetdb.testutils.services :refer [get-json]]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.reports :as reports]))

(defn prepare-and-submit-report
  []
  (let [report (:basic reports)
        certname (:certname report)]
    (blocking-command-post (utils/pdb-cmd-url) certname "store report" 8
                           (-> report
                               reports/report-query->wire-v8
                               (update :resources (fn [x] (map #(assoc % :corrective_change true) x)))))
    report))

(deftest query-resources-on-reports
  (with-ext-instances [pdb (utils/sync-config nil)]
      (let [report (prepare-and-submit-report)]
        (let [expected (->> report
                            reports/report-query->wire-v8
                            :resources
                            (map #(assoc % :corrective_change true))
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

(deftest query-for-corrective-change
  (with-ext-instances [pdb (utils/sync-config nil)]
    (let [report (prepare-and-submit-report)
          reports-response (first (get-json (utils/pdb-query-url) "/reports"))]
      (testing "corrective_change is returned at the top level and inside
                resources and their events."
        (is (not (nil? (:corrective_change reports-response))))
        (is (every? (comp not nil?)
                    (->> reports-response
                         :resource_events
                         :data
                         (map :corrective_change))))
        (is (every? (comp not nil?)
                    (->> reports-response
                         :resources
                         :data
                         (map :corrective_change))))
        (is (every? (comp not nil?)
                    (->> reports-response
                         :resources
                         :data
                         (map :events)
                         flatten
                         (map :corrective_change))))))))
