(ns com.puppetlabs.puppetdb.test.http.reports
  (:require [cheshire.core :as json]
            [com.puppetlabs.puppetdb.scf.storage :as scf-store]
            [com.puppetlabs.puppetdb.reports :as report]
            [puppetlabs.kitchensink.core :as kitchensink]
            [com.puppetlabs.puppetdb.examples.reports :refer [reports]]
            [com.puppetlabs.puppetdb.query :refer [remove-environment]]
            [com.puppetlabs.puppetdb.http :refer [remove-status]]
            [clojure.test :refer :all]
            [ring.mock.request :refer :all]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.puppetdb.testutils :refer [response-equal? assert-success!
                                                       get-request paged-results
                                                       deftestseq]]
            [com.puppetlabs.puppetdb.testutils.reports :refer [store-example-report!]]
            [clj-time.coerce :refer [to-date-time to-string]]
            [clj-time.core :refer [now]]
            [clojure.string :as str]))

(def endpoints [[:v3 "/v3/reports"]
                [:v4 "/v4/reports"]])

(use-fixtures :each fixt/with-test-db fixt/with-http-app)

(defn get-response
  [endpoint query] (fixt/*app* (get-request endpoint query)))

(defn report-response
 [report]
  (kitchensink/mapvals
    ;; the timestamps are already strings, but calling to-string on them forces
    ;; them to be coerced to dates and then back to strings, which normalizes
    ;; the timezone so that it will match the value returned form the db.
    to-string
    [:start-time :end-time]
    ;; the response won't include individual events, so we need to pluck those
    ;; out of the example report object before comparison
    (dissoc report :resource-events)))

(defn reports-response
  [version reports]
  (set (map (case version
              :v3 (comp #(remove-status % :v3) #(remove-environment % :v3) report-response)
              report-response) reports)))

(defn remove-receive-times
  [reports]
  ;; the example reports don't have a receive time (because this is
  ;; calculated by the server), so we remove this field from the response
  ;; for test comparison
  (map #(dissoc % :receive-time) reports))

(deftestseq query-by-certname
  [[version endpoint] endpoints]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))
        basic (assoc basic :hash report-hash)]

    (doseq [field (concat ["certname" "hash"]
                          (when-not (= :v3 version)
                            ;; "receive_time" is not tested here as it
                            ;; makes more sense in <,>,<=, >=, which
                            ;; will be added soon
                            ["puppet_version" "report_format" "configuration_version" "start_time" "end_time" "transaction_uuid" "status"]))
            :let [field-kwd (keyword (str/replace field #"_" "-"))]]
      (testing (format "should return all reports for a %s" field)
        (let [result (get-response endpoint ["=" field (get basic field-kwd)])]
          (case version
            :v3 (is (not-any? :environment (json/parse-string (:body result) true)))
            (do
              (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
              (is (every? #(= "unchanged" (:status %)) (json/parse-string (:body result) true)))))
          (response-equal?
           result
           (reports-response version [basic])
           remove-receive-times))))))

(deftestseq query-with-paging
  [[version endpoint] endpoints]

  (let [basic1        (:basic reports)
        basic1-hash   (:hash (store-example-report! basic1 (now)))
        basic2        (:basic2 reports)
        basic2-hash   (:hash (store-example-report! basic2 (now)))]

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str "should support paging through reports " label " counts")
        (let [results       (paged-results
                             {:app-fn  fixt/*app*
                              :path    endpoint
                              :query   ["=" "certname" (:certname basic1)]
                              :limit   1
                              :total   2
                              :include-total  count?})]
          (is (= 2 (count results)))
          (is (= (reports-response version
                                   [(assoc basic1 :hash basic1-hash)
                                    (assoc basic2 :hash basic2-hash)])
                 (set (remove-receive-times results)))))))))

(deftestseq invalid-queries
  [[version endpoint] endpoints]

  (let [response (get-response endpoint ["<" "environment" 0])]
    (if (= version :v3)
      (is (re-matches #".*query operator '<' is unknown" (:body response)))
      (is (re-matches #".*Query operators .*<.* not allowed .* environment" (:body response))))
    (is (= 400 (:status response))))

  (let [response (get-response endpoint ["=" "timestamp" 0])]
    (if (= version :v3)
      (is (re-find #"'timestamp' is not a valid query term" (:body response)))
      (is (re-find #"'timestamp' is not a queryable object for reports" (:body response))))
    (is (= 400 (:status response))))

  (when (= version :v3)
    (let [response (get-response endpoint ["=" "environment" "FOO"])]
      (is (re-find #"'environment' is not a valid query term" (:body response)))
      (is (= 400 (:status response))))))

(deftestseq query-by-status
  [[version endpoint] endpoints
   :when (not= version :v3)]

  (let [basic (:basic reports)
        hash1 (:hash (store-example-report! basic (now)))
        basic2 (:basic2 reports)
        hash2 (:hash (store-example-report! basic2 (now)))
        basic3 (assoc (:basic3 reports) :status "changed")
        hash3 (:hash (store-example-report! basic3 (now)))
        basic4 (assoc (:basic4 reports) :status "failed")
        hash4 (:hash (store-example-report! basic4 (now)))]

    (testing "should return all reports for a certname"
      (let [unchanged-result (get-response endpoint ["=" "status" "unchanged"])
            unchanged-reports (json/parse-string (:body unchanged-result) true)
            changed-result (get-response endpoint ["=" "status" "changed"])
            changed-reports (json/parse-string (:body changed-result) true)
            failed-result (get-response endpoint ["=" "status" "failed"])
            failed-reports (json/parse-string (:body failed-result) true)]

        (is (= 2 (count unchanged-reports)))
        (is (every? #(= "unchanged" (:status %)) unchanged-reports))

        (response-equal?
         unchanged-result
         (reports-response version [(assoc basic :hash hash1)
                                    (assoc basic2 :hash hash2)])
         remove-receive-times)

        (is (= 1 (count changed-reports)))
        (response-equal?
         changed-result
         (reports-response version [(assoc basic3 :hash hash3)])
         remove-receive-times)

        (is (= 1 (count failed-reports)))
        (response-equal?
         failed-result
         (reports-response version [(assoc basic4 :hash hash4)])
         remove-receive-times)))))

(deftestseq query-by-certname-with-environment
  [[version endpoint] endpoints
   :when (not= version :v3)]

  (let [basic         (:basic reports)
        report-hash   (:hash (store-example-report! basic (now)))]

    (testing "should return all reports for a certname"
      (let [result (get-response "/v4/environments/DEV/reports" ["=" "certname" (:certname basic)])]
        (is (every? #(= "DEV" (:environment %)) (json/parse-string (:body result) true)))
        (response-equal?
         result
         (reports-response version [(assoc basic :hash report-hash)])
         remove-receive-times)))
    (testing "PROD environment"
      (is (empty? (json/parse-string
                   (:body
                    (get-response "/v4/environments/PROD/reports"
                                  ["=" "certname" (:certname basic)]))))))))
