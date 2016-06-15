(ns puppetlabs.pe-puppetdb-extensions.catalogs-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [clj-time.core :refer [days ago now]]
            [clj-time.coerce :refer [to-timestamp]]
            [puppetlabs.kitchensink.core :as ks]
            [puppetlabs.puppetdb.cli.services :refer [query]]
            [puppetlabs.puppetdb.command :refer [enqueue-command]]
            [puppetlabs.puppetdb.cheshire :as json]
            [puppetlabs.puppetdb.export :as export]
            [puppetlabs.puppetdb.import :as import]
            [puppetlabs.puppetdb.testutils :as tu]
            [puppetlabs.puppetdb.testutils.catalogs :as tuc]
            [puppetlabs.puppetdb.testutils.cli
             :refer [get-nodes get-catalogs get-factsets get-reports munge-tar-map
                     example-catalog example-report example-facts example-certname]]
            [puppetlabs.puppetdb.testutils.facts :as tuf]
            [puppetlabs.puppetdb.testutils.reports :as tur]
            [puppetlabs.puppetdb.testutils.services :refer [get-json] :as svc-utils]
            [puppetlabs.pe-puppetdb-extensions.testutils :as utils
             :refer [blocking-command-post with-ext-instances]]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.puppetdb.examples.reports :refer [reports]]
            [puppetlabs.puppetdb.examples :refer [wire-catalogs]]
            [puppetlabs.puppetdb.random :refer [random-string]]
            [puppetlabs.puppetdb.reports :as reports]))

(defn generate-events [catalog-resource]
  (for [[field value] (:parameters catalog-resource)]
    {:status "success"
     :timestamp (now)
     :property field
     :old_value (random-string (count value))
     :new_value value
     :message (random-string 20)}))

(defn catalog-resource->report-resource [catalog-resource]
  {:events (generate-events catalog-resource)
   :file (:file catalog-resource)
   :line (:line catalog-resource)
   :resource_type (:type catalog-resource)
   :resource_title (:title catalog-resource)
   :skipped false
   :timestamp (now)
   :containment_path [(random-string 10)]})

(defn unify-report-with-catalog
  "Given a catalog and report, munge the report to make it look like it's
  associated with said catalog"
  [report
   {:keys [certname
           resources
           catalog_uuid]
    :as catalog}]
  (assoc report
         :resources (map catalog-resource->report-resource resources)
         :certname certname))

(deftest query-resources-on-reports
  (with-ext-instances [pdb (utils/sync-config nil)]
    (let [timestamps [(now) (-> 1 days ago) (-> 2 days ago)]
          certname "foo.local"
          example-catalog (-> (get-in wire-catalogs [9 :basic])
                              (assoc :certname certname))
          example-report (-> (:basic reports)
                             (assoc :certname certname)
                             reports/report-query->wire-v8
                             (unify-report-with-catalog example-catalog))]
      (doseq [timestamp timestamps
              :let [tx-uuid (ks/uuid)
                    catalog-uuid (ks/uuid)]]
        (->> (assoc example-report
                    :transaction_uuid tx-uuid
                    :catalog_uuid catalog-uuid
                    :producer_timestamp timestamp)
             (blocking-command-post (utils/pdb-cmd-url) example-certname
                                    "store report" 8))
        (->> (assoc example-catalog
                    :transaction_uuid tx-uuid
                    :catalog_uuid catalog-uuid
                    :producer_timestamp timestamp)
             (blocking-command-post (utils/pdb-cmd-url) example-certname
                                    "replace catalog" 9)))
      (testing "historical catalogs views have the right amount of data"
       (let [historical-catalogs (get-json (utils/pe-pdb-url) "/historical-catalogs")
             resource-graphs (get-json (utils/pe-pdb-url) "/resource-graphs")]
         (is (= (count timestamps)
                (count historical-catalogs)
                (count resource-graphs)))
         (is (= (count (:data (:resources historical-catalogs)))
                (count (:resources resource-graphs))))
         (is (= (set (->> (get-in historical-catalogs [:resources :data])
                          (map (juxt :type :title :parameters))))
                (set (->> (:resources resource-graphs)
                          (map (juxt :type :title :parameters))))))))

      (testing "paging options and queries work per usual"
        (let [[resource-graph :as resource-graphs]
              (get-json (utils/pe-pdb-url) "/resource-graphs"
                        {:query-params
                         {:query (json/generate-string [:= :certname certname])
                          :limit 1
                          :order_by (json/generate-string
                                     [{:field :producer_timestamp :order :desc}])}})]
          (is (= 1 (count resource-graphs)))
          (is (= (to-timestamp (first timestamps))
                 (to-timestamp (:producer_timestamp resource-graph))))))

      (testing "historical catalogs matches catalogs endpoint"
        (let [historical-catalogs (get-json (utils/pe-pdb-url) "/historical-catalogs"
                                            {:query-params
                                             {:query (json/generate-string [:= :certname certname])
                                              :limit 1
                                              :order_by (json/generate-string
                                                         [{:field :producer_timestamp :order :desc}])}})
              normal-catalogs (get-json (utils/pdb-query-url) "/catalogs"
                                        {:query-params
                                         {:query (json/generate-string [:= :certname certname])}})]
          (is (= (map #(dissoc % :edges :resources) historical-catalogs)
                 (map #(dissoc % :edges :resources) normal-catalogs)))
          (is (= (sort-by (juxt :source_title :target_title)
                          (get-in (first historical-catalogs) [:edges :data]))
                 (sort-by (juxt :source_title :target_title)
                          (get-in (first normal-catalogs) [:edges :data]))))
          (is (= (sort-by (juxt :type :title)
                          (get-in (first historical-catalogs) [:resources :data]))
                 (sort-by (juxt :type :title)
                          (map #(dissoc % :resource)
                               (get-in (first normal-catalogs) [:resources :data])))))

          (testing "and so the child data endpoints"
            (let [historical-catalog (first historical-catalogs)
                  historical-catalog-edges (get-json (utils/pe-pdb-url)
                                                     (str "/historical-catalogs/"
                                                          (:catalog_uuid historical-catalog)
                                                          "/edges"))
                  historical-catalog-resources (get-json (utils/pe-pdb-url)
                                                         (str "/historical-catalogs/"
                                                              (:catalog_uuid historical-catalog)
                                                              "/resources"))]
              (is (= (get-in historical-catalog [:edges :data])
                     historical-catalog-edges))
              (is (= (get-in historical-catalog [:resources :data])
                     historical-catalog-resources))))))

      (testing "when data is missing"
        (testing "when there is no report for a catalog"
          (->> (assoc example-catalog
                      :transaction_uuid (ks/uuid)
                      :certname "bar.example.com"
                      :producer_timestamp (-> 3 days ago))
               (blocking-command-post (utils/pdb-cmd-url) "bar.example.com"
                                      "replace catalog" 9))

          (let [resource-graphs
                (get-json (utils/pe-pdb-url) "/resource-graphs"
                          {:query-params
                           {:query (json/generate-string [:= :certname "bar.example.com"])}})]
            (is (empty? resource-graphs))))

        (testing "when there is no catalog for a report"
          (->> (assoc example-report
                      :transaction_uuid (ks/uuid)
                      :certname "baz.lan"
                      :producer_timestamp (-> 3 days ago))
               (blocking-command-post (utils/pdb-cmd-url) "baz.lan"
                                      "store report" 8))

          (let [resource-graphs
                (get-json (utils/pe-pdb-url) "/resource-graphs"
                          {:query-params
                           {:query (json/generate-string [:= :certname "baz.lan"])}})]
            (is (= 1 (count resource-graphs)))
            (is (not (nil? (:resources (first resource-graphs)))))
            ;; parameters and edges come from the catalog
            (is (every? nil? (map :parameters (:resources (first resource-graphs)))))
            (is (empty? (:edges (first resource-graphs))))))))))


;; This is a copy of the test in `puppetlabs.puppetdb.admin-test`
(deftest test-export-works-in-pe
  (let [export-out-file (tu/temp-file "export-test" ".tar.gz")]

    (with-ext-instances [pdb (utils/sync-config nil)]
     (fn []
       (is (empty? (get-nodes)))

       (blocking-command-post (svc-utils/pdb-cmd-url) example-certname
                              "replace catalog" 9 example-catalog)
       (blocking-command-post (svc-utils/pdb-cmd-url) example-certname
                              "store report" 8 example-report)
       (blocking-command-post (svc-utils/pdb-cmd-url) example-certname
                              "replace facts" 5 example-facts)

       (is (= (tuc/munge-catalog example-catalog)
              (tuc/munge-catalog (get-catalogs example-certname))))
       (is (= [example-report] (get-reports example-certname)))
       (is (= (tuf/munge-facts example-facts)
              (tuf/munge-facts (get-factsets example-certname))))

       (let [query-fn (partial query (tk-app/get-service svc-utils/*server* :PuppetDBServer))]
         (export/export! export-out-file query-fn))))

    (with-ext-instances [pdb (utils/sync-config nil)]
      (fn []
        (is (empty? (get-nodes)))

        (let [dispatcher (tk-app/get-service svc-utils/*server*
                                             :PuppetDBCommandDispatcher)
              submit-command-fn (partial enqueue-command dispatcher)]
          (import/import! export-out-file submit-command-fn))

        @(tu/block-until-results 100 (first (get-catalogs example-certname)))
        @(tu/block-until-results 100 (first (get-reports example-certname)))
        @(tu/block-until-results 100 (first (get-factsets example-certname)))

        (is (= (tuc/munge-catalog example-catalog)
               (tuc/munge-catalog (get-catalogs example-certname))))
        (is (= [example-report] (get-reports example-certname)))
        (is (= (tuf/munge-facts example-facts)
               (tuf/munge-facts (get-factsets example-certname))))))))
