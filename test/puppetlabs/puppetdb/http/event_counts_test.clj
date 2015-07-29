(ns puppetlabs.puppetdb.http.event-counts-test
  (:require [puppetlabs.puppetdb.http :as http]
            [puppetlabs.puppetdb.fixtures :as fixt]
            [clojure.java.io :refer [resource]]
            [puppetlabs.puppetdb.jdbc :as jdbc]
            [puppetlabs.puppetdb.cheshire :as json]
            [clojure.test :refer :all]
            [clojure.walk :refer [keywordize-keys]]
            [puppetlabs.puppetdb.examples.reports :refer :all]
            [puppetlabs.puppetdb.testutils.event-counts :refer [get-response]]
            [puppetlabs.puppetdb.testutils.catalogs :as testcat]
            [puppetlabs.puppetdb.testutils :refer [response-equal? paged-results deftestseq]]
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
  [[version endpoint] endpoints]

  (store-example-report! (:basic reports) (now))

  (testing "summarize_by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"] "illegal-summarize-by" {} true)
          body      (get response :body "null")]
      (is (= (:status response) http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize_by': 'illegal-summarize-by'" body))))

  (testing "count_by rejects unsupported values"
    (let [response  (get-response endpoint
                                  ["=" "certname" "foo.local"] "certname"
                                  {"count_by" "illegal-count-by"} true)
          body      (get response :body "null")]
      (is (= (:status response) http/status-bad-request))
      (is (re-find #"Unsupported value for 'count_by': 'illegal-count-by'" body))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  #{{:subject_type "containing_class"
                       :subject {:title "Foo"}
                       :failures 0
                       :successes 0
                       :noops 0
                       :skips 1}}
          response  (get-response endpoint
                                  ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                  "containing_class"
                                  {"count_by"      "certname"
                                   "counts_filter" ["<" "successes" 1]})]
      (response-equal? response expected)))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  #{{:subject_type "resource"
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
                         :skips           0}
                        {:subject_type "resource"
                         :subject {:type "Notify" :title "hi"}
                         :failures        0
                         :successes       0
                         :noops           0
                         :skips           1}}
            results (paged-results
                     {:app-fn  fixt/*app*
                      :path    endpoint
                      :query   [">" "timestamp" 0]
                      :params  {:summarize_by "resource"
                                :order_by (json/generate-string [{"field" "resource_title"}])}
                      :limit   1
                      :total   (count expected)
                      :include_total count?})]
        (is (= (count expected) (count results)))
        (is (= expected (set results)))))))

(deftestseq query-distinct-event-counts
  [[version endpoint] endpoints]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (:basic3 reports) (now))
  (testcat/replace-catalog (json/generate-string example-catalog))
  (testing "should only count the most recent event for each resource"
    (are [query result]
         (response-equal? (get-response endpoint
                                        query
                                        "resource"
                                        {"distinct_resources" true
                                         "distinct_start_time" 0
                                         "distinct_end_time" (now)})
                          result)

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
  [[version endpoint] endpoints]

  (store-example-report! (:basic reports) (now))
  (store-example-report! (assoc (:basic2 reports)
                           :certname "bar.local"
                           :environment "PROD") (now))
  (are [result query] (response-equal? (get-response endpoint
                                                     query
                                                     "resource")
                                       result)
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
