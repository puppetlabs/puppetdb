(ns puppetlabs.puppetdb.query.catalogs-test
  (:require [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.query.catalogs :as c]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.fixtures :refer :all]
            [clojure.java.io :refer [resource]]))

(use-fixtures :each with-test-db)

(deftest catalog-query
  (let [catalog-str (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
        {:strs [name version transaction_uuid environment] :as catalog} (json/parse-string
                                                                          catalog-str)]
    (testcat/replace-catalog catalog-str)
    (testing "status"
      (is (= (testcat/munged-canonical->wire-format :v5 (json/parse-string catalog-str true))
             (testcat/munged-canonical->wire-format :v5 (c/status :v4 name)))))))

(def data-seq (-> (slurp "./test-resources/puppetlabs/puppetdb/cli/export/catalog-query-rows.json")
                      (json/parse-string)
                      (keywordize-keys)))

(def expected-resources
  [{:resources
    [{:tags  ["stage"],
      :type "Stage",
      :title "main",
      :parameters  {:alias  ["main"], :name "main"},
      :exported false}
     {:tags  ["settings" "class"],
      :type "Class",
      :title "Settings",
      :parameters  {},
      :exported false}
     {:tags  ["class"],
      :type "Class",
      :title "main",
      :parameters  {:alias  ["main"], :name "main"},
      :exported false}
     {:tags  ["class" "hi" "notify"],
      :type "Notify",
      :title "hi",
      :line 3,
      :parameters  {:message "Hi world"},
      :exported false,
      :file "/home/wyatt/.puppet/manifests/site.pp"}],
    :edges
    [{:source  {:type "Stage", :title "main"},
      :target  {:type "Class", :title "Settings"},
      :relationship "contains"}
     {:source  {:type "Class", :title "main"},
      :target  {:type "Notify", :title "hi"},
      :relationship "contains"}
     {:source  {:type "Stage", :title "main"},
      :target  {:type "Class", :title "main"},
      :relationship "contains"}],
    :producer_timestamp "2014-11-20T03:37:35Z",
    :transaction_uuid "99823770-9802-4718-a9e7-5aa615db2398",
    :hash "41099460fa54b5d4853ea6f2624870dbd860f649",
    :environment "production",
    :version "1416454655",
    :name "myfakehostname"}
   {:resources
    [{:tags  ["stage"],
      :type "Stage",
      :title "main",
      :parameters  {:alias  ["main"], :name "main"},
      :exported false}
     {:tags  ["settings" "class"],
      :type "Class",
      :title "Settings",
      :parameters  {},
      :exported false}
     {:tags  ["class"],
      :type "Class",
      :title "main",
      :parameters  {:alias  ["main"], :name "main"},
      :exported false}
     {:tags  ["class" "hi" "notify"],
      :type "Notify",
      :title "hi",
      :line 3,
      :parameters  {:message "Hi world"},
      :exported false,
      :file "/home/wyatt/.puppet/manifests/site.pp"}],
    :edges
    [{:source  {:type "Stage", :title "main"},
      :target  {:type "Class", :title "Settings"},
      :relationship "contains"}
     {:source  {:type "Class", :title "main"},
      :target  {:type "Notify", :title "hi"},
      :relationship "contains"}
     {:source  {:type "Stage", :title "main"},
      :target  {:type "Class", :title "main"},
      :relationship "contains"}],
    :producer_timestamp "2014-11-20T02:15:20Z",
    :transaction_uuid "9a3c8da6-f48c-4567-b24e-ddae5f80a6c6",
    :hash "e1a4610ecbb3483fa5e637f42374b2cc46d06474",
    :environment "production",
    :version "1416449720",
    :name "desktop.localdomain"}])

(deftest structured-data-seq
  (testing "structured data seq gets correct result"
    (is (= expected-resources (c/structured-data-seq :v4 data-seq))))

  (testing "laziness of collapsing fns"
    (let [ten-billion 10000000000]
      (is (= 10
             (count (take 10
                          (c/structured-data-seq
                            :v4 (mapcat
                                  (fn [certname]
                                    (take 4
                                          (-> (first data-seq)
                                              (assoc :name certname :hash certname)
                                              (repeat))))
                                  (map #(str "foo" % ".com") (range 0 ten-billion))))))))))
  (testing "collapse resources"
    (let [expected-result
          #{{:tags ["stage"], :type "Stage", :title "main", :parameters {:alias ["main"], :name "main"}, :exported false}
            {:tags ["settings" "class"], :type "Class", :title "Settings", :parameters {}, :exported false}
            {:tags ["class"], :type "Class", :title "main", :parameters {:alias ["main"], :name "main"}, :exported false}
            {:tags ["class" "hi" "notify"], :type "Notify", :title "hi", :line 3,
             :parameters {:message "Hi world"}, :exported false, :file "/home/wyatt/.puppet/manifests/site.pp"}}]
    (is (= expected-result (reduce c/collapse-resources #{} data-seq)))))

  (testing "collapse edges"
    (let [expected-result
          #{{:source {:type "Stage", :title "main"}, :target {:type "Class", :title "Settings"}, :relationship "contains"}
            {:source {:type "Class", :title "main"}, :target {:type "Notify", :title "hi"}, :relationship "contains"}
            {:source {:type "Stage", :title "main"}, :target {:type "Class", :title "main"}, :relationship "contains"}}]
      (is (= expected-result (reduce c/collapse-edges #{} data-seq))))))
