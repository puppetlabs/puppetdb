(ns puppetlabs.puppetdb.http.event-counts-test
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.java.io :refer [resource]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [puppetlabs.puppetdb.testutils :refer [paged-results deftestseq]]
            [puppetlabs.puppetdb.testutils.http :refer [query-response query-result
                                                        vector-param
                                                        ordered-query-result]]
            [puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]
            [clj-time.core :refer [now]]))

(def endpoints [[:v4 "/v4/event-counts"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(def example-catalog
  (-> (slurp (resource "puppetlabs/puppetdb/cli/export/tiny-catalog.json"))
      json/parse-string
      keywordize-keys
      (assoc :certname "foo.local")))

(deftestseq query-event-counts
  [[version endpoint] endpoints
   method [:get :post]]

  (store-example-report! (:basic reports) (now))

  (testing "summarize_by rejects unsupported values"
    (let [{:keys [body status]} (query-response method endpoint
                                                ["=" "certname" "foo.local"]
                                                {:summarize_by "illegal-summarize-by"})]
      (is (= status http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize_by': 'illegal-summarize-by'"
                   body))))

  (testing "count_by rejects unsupported values"
    (let [{:keys [status body]}  (query-response
                                   method endpoint
                                   ["=" "certname" "foo.local"]
                                   {:summarize_by "certname"
                                    :count_by "illegal-count-by"})]
      (is (= status http/status-bad-request))
      (is (re-find #"Unsupported value for 'count_by': 'illegal-count-by'"
                   body))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  #{{:subject_type "containing_class"
                       :subject {:title "Foo"}
                       :failures 0
                       :successes 0
                       :noops 0
                       :skips 1}}
          response  (query-result
                      method endpoint
                      ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                      {:summarize_by "containing_class"
                       :count_by      "certname"
                       :counts_filter (vector-param method ["<" "successes" 1])})]
      (is (= response expected))))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  [{:subject_type "resource"
                        :subject {:type "Notify" :title "hi"}
                        :failures        0
                        :successes       0
                        :noops           0
                        :skips           1}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yar"}
                        :failures        0
                        :successes       1
                        :noops           0
                        :skips           0}
                       {:subject_type "resource"
                        :subject {:type "Notify" :title "notify, yo"}
                        :failures        0
                        :successes       1
                        :noops           0
                        :skips           0}]
            results (ordered-query-result
                      method
                      endpoint
                      [">" "timestamp" 0]
                      {:summarize_by "resource"
                       :order_by (vector-param method [{"field" "resource_title"}])
                       :include_total true})]
        (is (= (count expected) (count results)))
        (is (= expected results))))))

(deftestseq query-distinct-event-counts
  [[version endpoint] endpoints
   method [:get :post]]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (:basic3 reports) (now))
  (testcat/replace-catalog (json/generate-string example-catalog))
  (testing "should only count the most recent event for each resource"
    (are [query result]
         (is (= (query-result method endpoint query
                              {:summarize_by "resource"
                               :distinct_resources true
                               :distinct_start_time 0
                               :distinct_end_time (now)})
                result))

         ["=" "certname" "foo.local"]
         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}

         nil
         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}

         ["~" "certname" ".*"]
         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}

         ["~" "environment" ".*"]
         #{{:subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}
            :failures 0
            :successes 1
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}
            :failures 1
            :successes 0
            :noops 0
            :skips 0}
           {:subject_type "resource"
            :subject {:type "Notify" :title "hi"}
            :failures 0
            :successes 0
            :noops 0
            :skips 1}}

         ["~" "property" ".*"]
         #{{:failures 0
            :successes 1
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}}
           {:failures 1
            :successes 0
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}}}

         ["in" "certname" ["extract" "certname"
                           ["select_resources" ["~" "certname" ".*"]]]]
         #{{:failures 0
            :successes 1
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}}
           {:failures 1
            :successes 0
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}}
           {:failures 0
            :successes 0
            :noops 0
            :skips 1
            :subject_type "resource"
            :subject {:type "Notify" :title "hi"}}}

         ["in" "certname" ["extract" "certname"
                           ["select_resources" ["~" "tag" ".*"]]]]
         #{{:failures 0
            :successes 1
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yo"}}
           {:failures 1
            :successes 0
            :noops 0
            :skips 0
            :subject_type "resource"
            :subject {:type "Notify" :title "notify, yar"}}
           {:failures 0
            :successes 0
            :noops 0
            :skips 1
            :subject_type "resource"
            :subject {:type "Notify" :title "hi"}}})))

(deftestseq query-with-environment
  [[version endpoint] endpoints
   method [:get :post]]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (assoc (:basic2 reports)
                           :certname "bar.local"
                           :environment "PROD") (now))
  (are [result query] (is (= (query-result method endpoint query
                                           {:summarize_by "resource"})
                             result))
       #{{:subject_type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "tmp-directory"}}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "puppet-managed-file"}}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject
          {:type "Notify", :title "Creating tmp directory at /Users/foo/tmp"}}}
       nil

       #{{:subject_type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["=" "environment" "DEV"]

       #{{:subject_type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["~" "environment" "DE.*"]

       #{{:subject_type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}}
       ["not" ["=" "environment" "PROD"]]

       #{{:subject_type "resource"
          :subject {:type "Notify" :title "notify, yo"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "notify, yar"}
          :failures 0
          :successes 1
          :noops 0
          :skips 0}
         {:subject_type "resource"
          :subject {:type "Notify" :title "hi"}
          :failures 0
          :successes 0
          :noops 0
          :skips 1}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "tmp-directory"}}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject {:type "File", :title "puppet-managed-file"}}
         {:subject_type "resource",
          :noops 0,
          :skips 0,
          :successes 1,
          :failures 0,
          :subject
          {:type "Notify", :title "Creating tmp directory at /Users/foo/tmp"}}}
       ["OR"
        ["=" "environment" "PROD"]
        ["=" "environment" "DEV"]]))
