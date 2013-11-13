(ns com.puppetlabs.puppetdb.test.http.v3.facts
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [cheshire.core :as json]
            [com.puppetlabs.puppetdb.testutils :as testutils]
            [puppetlabs.kitchensink.core :refer [parse-int]])
  (:use clojure.test
        [com.puppetlabs.puppetdb.fixtures]
        [clj-time.core :only [now]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

(defn paged-results
  [query limit total count?]
  (testutils/paged-results
    {:app-fn  *app*
     :path    "/v3/facts"
     :query   query
     :limit   limit
     :total   total
     :include-total  count?}))

(deftest fact-queries
  (let [facts1 {"domain" "testing.com"
                "hostname" "foo1"
                "kernel" "Linux"
                "operatingsystem" "Debian"
                "some_version" "1.3.7+build.11.e0f985a"
                "uptime_seconds" "4000"}
        facts2 {"domain" "testing.com"
                "hostname" "foo2"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" "6000"}]
    (with-transacted-connection *db*
      (scf-store/add-certname! "foo1")
      (scf-store/add-certname! "foo2")
      (scf-store/add-facts! "foo1" facts1 (now))
      (scf-store/add-facts! "foo2" facts2 (now)))

    (doseq [[label counts?] [["without" false]
                             ["with" true]]]
      (testing (str "should support paging through facts " label " counts")
        (let [results (paged-results
                        ["=" "certname" "foo1"]
                        2 (count facts1) counts?)]
          (is (= (count facts1) (count results)))
          (is (= (set (map (fn [[k v]]
                             {:certname "foo1"
                              :name     k
                              :value    v})
                          facts1))
                (set results))))))))

(defn- raw-query-facts
  [query paging-options]

  (let [{:keys [limit offset include-total]
         :or {limit Integer/MAX_VALUE
              include-total true
              offset 0}}  paging-options

        {:keys [headers body]} (testutils/paged-results* (assoc paging-options
                                                           :app-fn  *app*
                                                           :path    "/v3/facts"
                                                           :offset offset
                                                           :limit limit
                                                           :include-total include-total))]
    {:results body
     :count (when-let [rec-count (get headers "X-Records")]
              (parse-int rec-count))}))

(defn- query-facts
  [paging-options]
  (:results (raw-query-facts nil paging-options)))

(deftest paging-results
  (let [f1         {:certname "a.local" :name "hostname"    :value "a-host"}
        f2         {:certname "b.local" :name "uptime_days" :value "4"}
        f3         {:certname "c.local" :name "hostname"    :value "c-host"}
        f4         {:certname "d.local" :name "uptime_days" :value "2"}
        fact-count 4]

    (scf-store/add-certname! "c.local")
    (scf-store/add-facts! "c.local" {"hostname" "c-host"} (now))
    (scf-store/add-certname! "a.local")
    (scf-store/add-facts! "a.local" {"hostname" "a-host"} (now))
    (scf-store/add-certname! "d.local")
    (scf-store/add-facts! "d.local" {"uptime_days" "2"} (now))
    (scf-store/add-certname! "b.local")
    (scf-store/add-facts! "b.local" {"uptime_days" "4"} (now))

    (testing "include total results count"
      (let [actual (:count (raw-query-facts nil {:include-total true}))]
        (is (= actual fact-count))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 fact-count]]]
        (let [results (query-facts {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (re-matches #"Unrecognized column 'invalid-field' specified in :order-by.*"
                        (:body (*app* (testutils/get-request "/v3/facts" nil {:order-by (json/generate-string [{"field" "invalid-field" "order" "ASC"}])}))))))
      (testing "alphabetical fields"
        (doseq [[order expected] [["ASC" [f1 f2 f3 f4]]
                                  ["DESC" [f4 f3 f2 f1]]]]
          (testing order
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[name-order value-order] expected] [[["DESC" "ASC"]  [f4 f2 f1 f3]]
                                                     [["DESC" "DESC"] [f2 f4 f3 f1]]
                                                     [["ASC" "DESC"]  [f3 f1 f2 f4]]
                                                     [["ASC" "ASC"]   [f1 f3 f4 f2]]]]
          (testing (format "name %s value %s" name-order value-order)
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "name" "order" name-order}
                                                                     {"field" "value" "order" value-order}])}})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [["ASC"  [[0 [f1 f2 f3 f4]]
                                                   [1 [f2 f3 f4]]
                                                   [2 [f3 f4]]
                                                   [3 [f4]]
                                                   [4 []]]]
                                          ["DESC" [[0 [f4 f3 f2 f1]]
                                                   [1 [f3 f2 f1]]
                                                   [2 [f2 f1]]
                                                   [3 [f1]]
                                                   [4 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (query-facts
                          {:params {:order-by (json/generate-string [{"field" "certname" "order" order}])}
                           :offset offset})]
              (is (= actual expected)))))))))
