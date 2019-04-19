(ns puppetlabs.puppetdb.http.aggregate-event-counts-test
  (:require [puppetlabs.puppetdb.http :as http]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [puppetlabs.puppetdb.scf.storage :as scf-store]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils :refer [assert-success! ]]
            [puppetlabs.puppetdb.testutils.http
             :refer [are-error-response-headers
                     deftest-http-app
                     query-response
                     query-result
                     vector-param]]
            [puppetlabs.puppetdb.testutils.reports :refer [with-corrective-change
                                                           without-corrective-change
                                                           store-example-report!]]
            [puppetlabs.puppetdb.time :as coerce :refer [now]]))

(def endpoints [[:v4 "/v4/aggregate-event-counts"]])

;; Tests without corrective changes support

(deftest-http-app query-aggregate-event-counts
  [[version endpoint] endpoints
   method [:post :get]]

  (without-corrective-change
    (store-example-report! (:basic reports) (now))

    (testing "summarize_by rejects unsupported values"
      (let [{:keys [body headers status]}
            (query-response method endpoint
                            ["=" "certname" "foo.local"]
                            {:summarize_by "illegal-summarize-by"})]
        (is (= status http/status-bad-request))
        (are-error-response-headers headers)
        (is (re-find #"Unsupported value for 'summarize_by': 'illegal-summarize-by'" body))))

    (testing "count_by rejects unsupported values"
      (let [{:keys [status body headers]}
            (query-response method endpoint
                            ["=" "certname" "foo.local"]
                            {:summarize_by "certname"
                             :count_by "illegal-count-by"})]
        (is (= status http/status-bad-request))
        (are-error-response-headers headers)
        (is (re-find #"Unsupported value for 'count_by': 'illegal-count-by'" body))))

    (testing "summarize_by accepts multiple parameters"
      (let [expected #{{:successes 1
                        :failures 0
                        :noops 0
                        :skips 1
                        :total 1
                        :summarize_by "certname"}
                       {:successes 2
                        :skips 1
                        :failures 0
                        :noops 0
                        :total 3
                        :summarize_by "resource"}}
            result (query-result method endpoint
                                 ["=" "certname" "foo.local"]
                                 {:summarize_by "certname,resource"})]
        (is (= result expected))))

    (testing "nontrivial query using all the optional parameters"
      (let [expected {:successes 0
                      :failures 0
                      :noops 0
                      :skips 1
                      :total 1
                      :summarize_by "containing_class"}
            response (query-result method endpoint
                                   ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                   {:summarize_by "containing_class"
                                    :count_by      "certname"
                                    :counts_filter (vector-param method ["<" "successes" 1])})]
        (is (= (first response) expected))))))

(deftest-http-app query-distinct-event-counts
  [[version endpoint] endpoints
   method [:get :post]]

  (let [current-time (now)
        current-time-str (coerce/to-string current-time)
        expected #{{:successes 1
                    :skips 1
                    :failures 1
                    :noops 0
                    :total 3
                    :summarize_by "resource"}}
        facts {"domain" "testing.com"
                "hostname" "foo.local"
                "kernel" "Linux"
                "operatingsystem" "RedHat"
                "uptime_seconds" 6000}]
    (without-corrective-change
      (store-example-report! (:basic reports) current-time)
      (store-example-report! (:basic3 reports) current-time)
      (scf-store/add-facts! {:certname "foo.local"
                             :values facts
                             :timestamp (now)
                             :producer_timestamp (now)
                             :environment "DEV"
                             :producer "kings of wildcat"})
      (are [query] (= expected
                      (query-result method endpoint query
                                    {:summarize_by "resource"
                                     :distinct_resources true
                                     :distinct_start_time 0
                                     :distinct_end_time (now)}))
           nil
           ["=" "certname" "foo.local"]
           ["<=" "report_receive_time" current-time-str]
           ["<=" "run_start_time" current-time-str]
           ["<=" "run_end_time" current-time-str])
      (store-example-report! (:basic2 reports) (coerce/to-string (now)))

      (are [result query] (= result
                             (query-result method endpoint query
                                           {:summarize_by "resource"
                                            :distinct_resources true
                                            :distinct_start_time 0
                                            :distinct_end_time (now)}))

           #{{:summarize_by "resource"
              :successes 3
              :failures 0
              :noops 0
              :skips 0
              :total 3}}
           ["=" "latest_report?" true]

           #{{:summarize_by "resource"
              :successes 3
              :failures 0
              :noops 0
              :skips 0
              :total 3}}
           ["and" ["=" "latest_report?" true]
            ["in" "certname" ["extract" "certname" ["select_facts" ["=" "certname" "foo.local"]]]]]

           #{{:summarize_by "resource"
              :successes 4
              :failures 1
              :noops 0
              :skips 1
              :total 6}}
           []

           #{{:summarize_by "resource"
              :successes 1
              :failures 1
              :noops 0
              :skips 1
              :total 3}}
           ["=" "latest_report?" false]

           #{{:summarize_by "resource"
              :successes 3
              :failures 0
              :noops 0
              :skips 0
              :total 3}}
           ["and" ["=" "latest_report?" true] ["=" "certname" "foo.local"]]))))

(deftest-http-app query-with-environment
  [[version endpoint] endpoints
   method [:get :post]]
  (without-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (assoc (:basic2 reports)
                             :certname "bar.local"
                             :environment "PROD") (now))
    (are [result query] (= result
                           (query-result method endpoint query
                                         {:summarize_by "resource"}))
         #{{:successes 2
            :skips 1
            :failures 0
            :noops 0
            :total 3
            :summarize_by "resource"}}
         ["=" "environment" "DEV"]

         #{{:successes 5
            :skips 1
            :failures 0
            :noops 0
            :total 6
            :summarize_by "resource"}}
         nil

         #{{:successes 2
            :skips 1
            :failures 0
            :noops 0
            :total 3
            :summarize_by "resource"}}
         ["~" "environment" "DE"]

         #{{:successes 3
            :skips 0
            :failures 0
            :noops 0
            :total 3
            :summarize_by "resource"}}
         ["=" "environment" "PROD"]

         #{{:successes 3
            :skips 0
            :failures 0
            :noops 0
            :total 3
            :summarize_by "resource"}}
         ["~" "environment" "PR"]

         #{{:successes 5
            :skips 1
            :failures 0
            :noops 0
            :total 6
            :summarize_by "resource"}}
         ["~" "environment" "D"]

         #{{:successes 5
            :skips 1
            :failures 0
            :noops 0
            :total 6
            :summarize_by "resource"}}
         ["OR"
          ["=" "environment" "DEV"]
          ["=" "environment" "PROD"]]

         #{{:successes 0
            :skips 0
            :failures 0
            :noops 0
            :total 0
            :summarize_by "resource"}}
         ["<" "timestamp" 0]

         #{{:summarize_by "resource"
            :successes 5
            :failures 0
            :noops 0
            :skips 1
            :total 6}}
         ["=" "latest_report?" true])))

;; Tests with corrective changes support

(deftest-http-app query-aggregate-event-counts-with-corrective-changes
  [[version endpoint] endpoints
   method [:post :get]]

  (with-corrective-change
    (store-example-report! (:basic reports) (now))

    (testing "summarize_by accepts multiple parameters"
      (let [expected #{{:intentional_successes 1
                        :corrective_successes 1
                        :failures 0
                        :intentional_noops 0
                        :corrective_noops 0
                        :skips 1
                        :total 1
                        :summarize_by "certname"}
                       {:intentional_successes 1
                        :corrective_successes 1
                        :skips 1
                        :failures 0
                        :intentional_noops 0
                        :corrective_noops 0
                        :total 3
                        :summarize_by "resource"}}
            result (query-result method endpoint
                                 ["=" "certname" "foo.local"]
                                 {:summarize_by "certname,resource"})]
        (is (= result expected))))

    (testing "nontrivial query using all the optional parameters"
      (let [expected {:intentional_successes 0
                      :corrective_successes 0
                      :failures 0
                      :intentional_noops 0
                      :corrective_noops 0
                      :skips 1
                      :total 1
                      :summarize_by "containing_class"}
            response (query-result method endpoint
                                   ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                   {:summarize_by "containing_class"
                                    :count_by      "certname"
                                    :counts_filter (vector-param method ["<" "intentional_successes" 1])})]
        (is (= (first response) expected))))))

(deftest-http-app query-distinct-event-counts-with-corrective-changes
  [[version endpoint] endpoints
   method [:get :post]]

  (let [current-time (now)
        current-time-str (coerce/to-string current-time)
        expected #{{:intentional_successes 1
                    :corrective_successes 0
                    :skips 1
                    :failures 1
                    :intentional_noops 0
                    :corrective_noops 0
                    :total 3
                    :summarize_by "resource"}}]
    (with-corrective-change
      (store-example-report! (:basic reports) current-time)
      (store-example-report! (:basic3 reports) current-time)
      (are [query] (= expected
                      (query-result method endpoint query
                                    {:summarize_by "resource"
                                     :distinct_resources true
                                     :distinct_start_time 0
                                     :distinct_end_time (now)}))
           nil
           ["=" "certname" "foo.local"]
           ["<=" "report_receive_time" current-time-str]
           ["<=" "run_start_time" current-time-str]
           ["<=" "run_end_time" current-time-str])
      (store-example-report! (:basic2 reports) (coerce/to-string (now)))

      (are [result query] (= result
                             (query-result method endpoint query
                                           {:summarize_by "resource"
                                            :distinct_resources true
                                            :distinct_start_time 0
                                            :distinct_end_time (now)}))

           #{{:summarize_by "resource"
              :intentional_successes 3
              :corrective_successes 0
              :failures 0
              :intentional_noops 0
              :corrective_noops 0
              :skips 0
              :total 3}}
           ["=" "latest_report?" true]

           #{{:summarize_by "resource"
              :intentional_successes 4
              :corrective_successes 0
              :failures 1
              :intentional_noops 0
              :corrective_noops 0
              :skips 1
              :total 6}}
           []

           #{{:summarize_by "resource"
              :intentional_successes 1
              :corrective_successes 0
              :failures 1
              :intentional_noops 0
              :corrective_noops 0
              :skips 1
              :total 3}}
           ["=" "latest_report?" false]

           #{{:summarize_by "resource"
              :intentional_successes 3
              :corrective_successes 0
              :failures 0
              :intentional_noops 0
              :corrective_noops 0
              :skips 0
              :total 3}}
           ["and" ["=" "latest_report?" true] ["=" "certname" "foo.local"]]))))

(deftest-http-app query-with-environment-with-corrective-changes
  [[version endpoint] endpoints
   method [:get :post]]
  (with-corrective-change
    (store-example-report! (:basic reports) (now))
    (store-example-report! (assoc (:basic2 reports)
                             :certname "bar.local"
                             :environment "PROD") (now))
    (are [result query] (= result
                           (query-result method endpoint query
                                         {:summarize_by "resource"}))
         #{{:intentional_successes 1
            :corrective_successes 1
            :skips 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 3
            :summarize_by "resource"}}
         ["=" "environment" "DEV"]

         #{{:intentional_successes 4
            :corrective_successes 1
            :skips 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 6
            :summarize_by "resource"}}
         nil

         #{{:intentional_successes 1
            :corrective_successes 1
            :skips 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 3
            :summarize_by "resource"}}
         ["~" "environment" "DE"]

         #{{:intentional_successes 3
            :corrective_successes 0
            :skips 0
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 3
            :summarize_by "resource"}}
         ["=" "environment" "PROD"]

         #{{:intentional_successes 3
            :corrective_successes 0
            :skips 0
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 3
            :summarize_by "resource"}}
         ["~" "environment" "PR"]

         #{{:intentional_successes 4
            :corrective_successes 1
            :skips 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 6
            :summarize_by "resource"}}
         ["~" "environment" "D"]

         #{{:intentional_successes 4
            :corrective_successes 1
            :skips 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 6
            :summarize_by "resource"}}
         ["OR"
          ["=" "environment" "DEV"]
          ["=" "environment" "PROD"]]

         #{{:intentional_successes 0
            :corrective_successes 0
            :skips 0
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :total 0
            :summarize_by "resource"}}
         ["<" "timestamp" 0]

         #{{:summarize_by "resource"
            :intentional_successes 4
            :corrective_successes 1
            :failures 0
            :intentional_noops 0
            :corrective_noops 0
            :skips 1
            :total 6}}
         ["=" "latest_report?" true])))
