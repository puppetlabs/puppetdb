(ns com.puppetlabs.puppetdb.test.http.experimental.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [cheshire.core :as json])
  (:use clojure.test
        ring.mock.request
        com.puppetlabs.puppetdb.fixtures
        com.puppetlabs.puppetdb.examples.report
        [com.puppetlabs.puppetdb.testutils :only (response-equal? assert-success! paged-results get-request)]
        [com.puppetlabs.puppetdb.testutils.report :only [store-example-report!]]
        [clj-time.core :only [now]]))

(use-fixtures :each with-test-db with-http-app)

(def content-type-json pl-http/json-response-content-type)

(defn- json-encode-counts-filter
  [params]
  (if-let [counts-filter (params "counts-filter")]
    (assoc params "counts-filter" (json/generate-string counts-filter))
    params))

(defn- get-response
  ([query summarize-by]
    (get-response query summarize-by {}))
  ([query summarize-by extra-query-params]
    (get-response query summarize-by extra-query-params false))
  ([query summarize-by extra-query-params ignore-failure?]
    (let [response  (*app* (get-request
                       "/experimental/event-counts"
                       query
                       (-> extra-query-params
                         (assoc "summarize-by" summarize-by)
                         (json-encode-counts-filter))))]
      (when-not ignore-failure?
        (assert-success! response))
      response)))



(deftest query-event-counts
  (store-example-report! (:basic reports) (now))

  (testing "summarize-by rejects unsupported values"
    (let [response  (get-response ["=" "certname" "foo.local"] "illegal-summarize-by" {} true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'summarize-by': 'illegal-summarize-by'" body))))

  (testing "count-by rejects unsupported values"
    (let [response  (get-response ["=" "certname" "foo.local"]
                      "node"
                      {"count-by" "illegal-count-by"}
                      true)
          body      (get response :body "null")]
      (is (= (:status response) pl-http/status-bad-request))
      (is (re-find #"Unsupported value for 'count-by': 'illegal-count-by'" body))))

  (testing "nontrivial query using all the optional parameters"
    (let [expected  #{{:containing_class "Foo"
                       :failures 0
                       :successes 0
                       :noops 0
                       :skips 1}}
          response  (get-response ["or" ["=" "status" "success"] ["=" "status" "skipped"]]
                                   "containing-class"
                                   {"count-by"      "node"
                                    "counts-filter" ["<" "successes" 1]})]
      (response-equal? response expected)))

  (doseq [[label count?] [["without" false]
                          ["with" true]]]
    (testing (str "should support paging through event-counts " label " counts")
      (let [expected  #{{:resource_type   "Notify"
                         :resource_title  "notify, yar"
                         :failures        0
                         :successes       1
                         :noops           0
                         :skips           0}
                        {:resource_type   "Notify"
                         :resource_title  "notify, yo"
                         :failures        0
                         :successes       1
                         :noops           0
                         :skips           0}
                        {:resource_type   "Notify"
                         :resource_title  "hi"
                         :failures        0
                         :successes       0
                         :noops           0
                         :skips           1}}
            results (paged-results
                      {:app-fn  *app*
                       :path    "/experimental/event-counts"
                       :query   [">" "timestamp" 0]
                       :params  {:summarize-by "resource"}
                       :limit   1
                       :total   (count expected)
                       :include-total count?})]
        (is (= (count expected) (count results)))
        (is (= expected
              (set results)))))))
