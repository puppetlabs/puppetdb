(ns puppetlabs.puppetdb.http.nodes-test
  (:require
   [cheshire.core :as json]
   [clojure.string :refer [starts-with?]]
   [clojure.test :refer :all]
   [flatland.ordered.map :as omap]
   [puppetlabs.kitchensink.core :refer [keyset]]
   [puppetlabs.puppetdb.query-eng :refer [munge-fn-hook]]
   [puppetlabs.puppetdb.testutils :refer [paged-results] :as tu]
   [puppetlabs.puppetdb.testutils.http
    :refer [*app*
            are-error-response-headers
            deftest-http-app
            convert-response
            ordered-query-result
            query-result
            vector-param
            query-response]]
   [puppetlabs.puppetdb.testutils.nodes :refer [store-example-nodes]])
  (:import
   (java.net HttpURLConnection)))

(def endpoints [[:v4 "/v4/nodes"]])

(defn status-for-node
  "Returns status information for the given `node-name`"
  [method endpoint node-name]
  (-> (query-response method endpoint ["=" "certname" node-name])
      :body
      slurp
      (json/parse-string true)
      first))

(defn is-query-result
  [method endpoint query expected]
  (let [{:keys [body status]} (query-response method endpoint query)
        body   (if (string? body)
                 body
                 (slurp body))
        result (try
                 (json/parse-string body true)
                 (catch com.fasterxml.jackson.core.JsonParseException _
                   body))]

    (is (= (count expected)
           (count result))
        (str "Query was: " query))

    (doseq [res result]
      (is (= #{:certname :deactivated :expired :catalog_timestamp :facts_timestamp :report_timestamp
               :catalog_environment :facts_environment :report_environment
               :latest_report_status :latest_report_hash :latest_report_noop
               :latest_report_noop_pending :cached_catalog_status
               :latest_report_corrective_change :latest_report_job_id} (keyset res))
          (str "Query was: " query))
      (is (= (set expected) (set (mapv :certname result)))
          (str "Query was: " query)))

    (is (= HttpURLConnection/HTTP_OK status))))

(deftest-http-app node-queries
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [status-for-node' #(status-for-node method endpoint %)
        {:keys [web1 web2 db puppet]} (store-example-nodes)
        is-query-result' (fn [query expected] (is-query-result method endpoint query expected))]
    (testing "status objects should reflect fact/catalog activity"
      (testing "when node is active"
        (let [status (status-for-node' web1)]
          (is (nil? (:deactivated status)))
          (is (nil? (:expired status)))))

      (testing "when node has facts, but no catalog"
        (is (:facts_timestamp (status-for-node' web2)))
        (is (nil? (:catalog_timestamp (status-for-node' web2)))))

      (testing "when node has an associated catalog and facts"
        (is (:catalog_timestamp (status-for-node' web1)))
        (is (:facts_timestamp (status-for-node' web1)))))

    (testing "basic equality is supported for name"
      (is-query-result' ["=" "certname" "web1.example.com"] [web1]))

    (testing "equality is supported on facts_environment"
      (is-query-result' ["=" "facts_environment" "DEV"]
                        [web1 web2 puppet db]))

    (testing "query on last corrective_change status returns an empty result"
      (is-query-result' ["=" "latest_report_corrective_change" true] []))

    (testing "all nodes have last corrective_change status null"
      (is-query-result' ["null?" "latest_report_corrective_change" true] [db puppet web1 web2]))

    (testing "query on last job_id returns an empty result"
      (is-query-result' ["=" "latest_report_job_id" "1234567890"] []))

    (testing "query on last job_id returns web1"
      (is-query-result' ["=" "latest_report_job_id" "0987654321"] [web1]))

    (testing "all nodes have last job_id null"
      (is-query-result' ["null?" "latest_report_job_id" true] [db puppet web2]))

    (testing "regular expressions are supported for name"
      (is-query-result' ["~" "certname" "web\\d+.example.com"] [web1 web2])
      (is-query-result' ["~" "certname" "\\w+.example.com"] [db puppet web1 web2])
      (is-query-result' ["~" "certname" "example.net"] []))

    (testing "query that generates 0x00 byte error does not show the error type in the response"
      (let [certname (str "host-1" (char 0))
            {:keys [body status]} (query-response method endpoint ["=" "certname" certname])]
         (is (= 500 status))
         ;; pg-14 adds additional information onto the end
         (is (starts-with? body "ERROR: invalid byte sequence for encoding \"UTF8\": 0x00"))))

    (testing "querying on latest report hash works"
      (let [cert-hashes (query-result method endpoint ["extract"
                                                       ["certname" "latest_report_hash"]
                                                       ["~" "certname" ".*"]])]
        (doseq [{:keys [certname latest_report_hash]} cert-hashes]
          (let [body (query-result method endpoint ["=" "latest_report_hash" latest_report_hash])]
            (is (= certname (:certname (first body))))))))

    (testing "querying on latest report status works"
      (is-query-result' ["=" "latest_report_status" "success"] [])
      (is-query-result' ["=" "latest_report_status" "failure"] [])
      (is-query-result' ["=" "latest_report_status" "unchanged"] [web1 db puppet])
      (is (= #{{:latest_report_status nil} {:latest_report_status "unchanged"}}
             (query-result method endpoint ["extract" ["latest_report_status"]]))))

    (testing "querying on latest report noop works"
      (is-query-result' ["=" "latest_report_noop" true] [])
      (is-query-result' ["=" "latest_report_noop" false] [web1 db puppet]))

    (testing "querying on latest cached catalog status works"
      (is-query-result' ["=" "cached_catalog_status" "not_used"] [web1 db puppet])
      (is-query-result' ["=" "cached_catalog_status" nil] [web2]))

    (testing "basic equality works for facts, and is based on string equality"
      (is-query-result' ["=" ["fact" "operatingsystem"] "Debian"] [db web1 web2])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 10000] [web1])
      (is-query-result' ["=" ["fact" "uptime_seconds"] "10000"] [])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 10000.0] [web1])
      (is-query-result' ["in" ["fact" "uptime_seconds"] ["array" [10000.0]]] [web1])
      (is-query-result' ["in" ["fact" "uptime_seconds"] ["array" [10000 50000]]] [web1])
      (is-query-result' ["=" ["fact" "uptime_seconds"] true] [])
      (is-query-result' ["=" ["fact" "uptime_seconds"] 0] []))

    (testing "missing facts are not equal to anything"
      (is-query-result' ["=" ["fact" "fake_fact"] "something"] [])
      (is-query-result' ["in" ["fact" "fake_fact"] ["array" ["something" "something else" "a whole other thing entirely"]]] [])
      (is-query-result' ["not" ["=" ["fact" "fake_fact"] "something"]] [db puppet web1 web2]))

    (testing "arithmetic works on facts"
      (is-query-result' ["<" ["fact" "uptime_seconds"] 12000] [web1])
      (is-query-result' ["<" ["fact" "uptime_seconds"] 12000.0] [web1])
      (is-query-result' ["and" [">" ["fact" "uptime_seconds"] 10000] ["<" ["fact" "uptime_seconds"] 15000]] [web2])
      (is-query-result' ["<=" ["fact" "uptime_seconds"] 15000] [puppet web1 web2]))

    (testing "regular expressions work on facts"
      (is-query-result' ["~" ["fact" "ipaddress"] "192.168.1.11\\d"] [db puppet])
      (is-query-result' ["~" ["fact" "hostname"] "web\\d"] [web1 web2]))))

(deftest-http-app test-string-coercion-fail
  [[_version endpoint] endpoints
   method [:get :post]]
  (store-example-nodes)
  (let [{:keys [body status]} (query-response method endpoint ["<" ["fact" "uptime_seconds"] "12000"])]
    (is (= 400 status))
    (is (re-find #"not allowed on value '12000'" body))))

(deftest-http-app node-subqueries
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)]
    (are [query expected]
        (is-query-result method endpoint query expected)

      ;;;;;;;;;;;;
      ;; Fact subqueries
      ;;;;;;;;;;;;

      ;; In: select_facts
      ["in" "certname"
       ["extract" "certname"
        ["select_facts"
         ["and"
          ["=" "name" "operatingsystem"]
          ["=" "value" "Debian"]]]]]
      [db web1 web2]

      ;; In: from facts
      ["in" "certname"
       ["from" "facts"
        ["extract" "certname"
         ["and"
          ["=" "name" "operatingsystem"]
          ["=" "value" "Debian"]]]]]
      [db web1 web2]

      ;; Implicit subquery
      ["subquery" "facts"
       ["and"
        ["=" "name" "operatingsystem"]
        ["=" "value" "Debian"]]]
      [db web1 web2]

      ;;;;;;;;;;;
      ;; Fact_contents subqueries
      ;;;;;;;;;;;

      ;; In: select_facts
      ["in" "certname"
       ["extract" "certname"
        ["select_fact_contents"
         ["and"
          ["=" "name" "operatingsystem"]
          ["=" "value" "Debian"]]]]]
      [db web1 web2]

      ;; In: from facts
      ["in" "certname"
       ["from" "fact_contents"
        ["extract" "certname"
         ["and"
          ["=" "name" "operatingsystem"]
          ["=" "value" "Debian"]]]]]
      [db web1 web2]

      ;; Implicit subquery
      ["subquery" "fact_contents"
       ["and"
        ["=" "name" "operatingsystem"]
        ["=" "value" "Debian"]]]
      [db web1 web2]

      ;;;;;;;;;;;;;
      ;; Nodes with a class matching their hostname
      ;;;;;;;;;;;;;

      ;; In: select_<entity>
      ["in" "certname"
       ["extract" "certname"
        ["select_facts"
         ["and"
          ["=" "name" "hostname"]
          ["in" "value"
           ["extract" "title"
            ["select_resources"
             ["and"
              ["=" "type" "Class"]]]]]]]]]
      [web1]

      ;; In: from <entity>
      ["in" "certname"
       ["from" "facts"
        ["extract" "certname"
         ["and"
          ["=" "name" "hostname"]
          ["in" "value"
           ["extract" "title"
            ["select_resources"
             ["and"
              ["=" "type" "Class"]]]]]]]]]
      [web1]

      ;; Implicit subquery
      ["subquery" "facts"
       ["and"
        ["=" "name" "hostname"]
        ["in" "value"
         ["extract" "title"
          ["select_resources"
           ["and"
            ["=" "type" "Class"]]]]]]]
      [web1]

      ;;;;;;;;;;;;
      ;; Nodes with matching select-resources for file/line
      ;;;;;;;;;;;;

      ;; In: select_resources
      ["in" "certname"
       ["extract" "certname"
        ["select_resources"
         ["and"
          ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
          ["=" "line" 1]]]]]
      [db puppet web1]

      ;; In: from resources
      ["in" "certname"
       ["from" "resources"
        ["extract" "certname"
         ["and"
          ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
          ["=" "line" 1]]]]]
      [db puppet web1]

      ;; Implicit subquery
      ["subquery" "resources"
       ["and"
        ["=" "file" "/etc/puppet/modules/settings/manifests/init.pp"]
        ["=" "line" 1]]]
      [db puppet web1]

      ;;;;;;;;;;;;
      ;; Reports subquery
      ;;;;;;;;;;;;

      ;; In: select_reports
      ["in" "certname"
       ["extract" "certname"
        ["select_reports"
         ["=" "certname" db]]]]
      [db]

      ;; In: from reports
      ["in" "certname"
       ["from" "reports"
        ["extract" "certname"
         ["=" "certname" db]]]]
      [db]

      ;; Implicit subquery
      ["subquery" "reports"
       ["=" "certname" db]]
      [db]

      ;;;;;;;;;;;;;;
      ;; Catalogs subquery
      ;;;;;;;;;;;;;;

      ;; In: select_catalogs
      ["in" "certname"
       ["extract" "certname"
        ["select_catalogs"
         ["=" "certname" web1]]]]
      [web1]

      ;; In: from catalogs
      ["in" "certname"
       ["from" "catalogs"
        ["extract" "certname"
         ["=" "certname" web1]]]]
      [web1]

      ;; Implicit subquery
      ["subquery" "catalogs"
       ["=" "certname" web1]]
      [web1]

      ;;;;;;;;;;;;;;
      ;; Factsets subquery
      ;;;;;;;;;;;;;;

      ;; In: select_factsets
      ["in" "certname"
       ["extract" "certname"
        ["select_factsets"
         ["=" "certname" web2]]]]
      [web2]

      ;; In: from factsets
      ["in" "certname"
       ["from" "factsets"
        ["extract" "certname"
         ["=" "certname" web2]]]]
      [web2]

      ;; Implict subquery
      ["subquery" "factsets"
       ["=" "certname" web2]]
      [web2]

      ;;;;;;;;;;;;;
      ;; Events subquery
      ;;;;;;;;;;;;;

      ;; In: select_events
      ["in" "certname"
       ["extract" "certname"
        ["select_events"
         ["=" "certname" db]]]]
      [db]

      ;; In: from events
      ["in" "certname"
       ["from" "events"
        ["extract" "certname"
         ["=" "certname" db]]]]
      [db]

      ;; Implicit subquery
      ["subquery" "events"
       ["=" "certname" db]]
      [db]

      ;;;;;;;;;;;;;
      ;; Resource subquery
      ;;;;;;;;;;;;;

      ;; In: select_resources
      ["in" "certname"
       ["extract" "certname"
        ["select_resources"
         ["=" "certname" web1]]]]
      [web1]

      ;; In: from resources
      ["in" "certname"
       ["from" "resources"
        ["extract" "certname"
         ["=" "certname" web1]]]]
      [web1]

      ;; Implicit subquery
      ["subquery" "resources"
       ["=" "certname" web1]]
      [web1]))

  (testing "subqueries: invalid"
    ;; Ensure the v2 version of sourcefile/sourceline returns
    ;; a proper error.
    (let [query ["in" "certname"
                 ["extract" "certname"
                  ["select_resources"
                   ["and"
                    ["=" "sourcefile" "/etc/puppet/modules/settings/manifests/init.pp"]
                    ["=" "sourceline" 1]]]]]
          msg #"'sourcefile' is not a queryable object"]
      (testing (str endpoint " query: " query " should fail with msg: " msg)
        (let [{:keys [status body headers]} (query-response method endpoint query)]
          (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
          (are-error-response-headers headers)
          (is (re-find msg body)))))))

(deftest-http-app query-with-from
  [[_version endpoint] endpoints
   method [:get :post]]

  (testing "should change context to reports"
    (store-example-nodes)
    (let [expected ["db.example.com" "puppet.example.com" "web1.example.com"]
          result (query-result method endpoint ["in" "certname"
                                                ["from" "reports"
                                                 ["extract" "certname"]
                                                 ["order_by" ["certname"]]]])]
      (is (= expected (sort (mapv :certname result)))))))

(deftest-http-app query-with-pretty-printing
  [[_version endpoint] endpoints
   method [:get :post]]
  (store-example-nodes)
  (testing "should support pretty printing in reports"
    (let [results (slurp (:body (query-response method endpoint nil {:pretty true})))
          normal-results (slurp (:body (query-response method endpoint nil {:pretty false})))]
      (is (seq (json/parse-string results)))
      (is (> (count (clojure.string/split-lines results))
             (count (clojure.string/split-lines normal-results)))))))

(deftest-http-app query-with-explain-printing
  [[_version endpoint] endpoints
   method [:get :post]]
    (testing "should support explain when quering in reports"
      (let [results (slurp (:body (query-response method endpoint nil {:explain "analyze"})))]
        (is (seq (json/parse-string results)))
        (is (= true (contains? (first (json/parse-string results)) "query plan"))))))

(deftest-http-app aggregate-functions-on-nodes
  [[_version endpoint] endpoints
   method [:get :post]]

  (store-example-nodes)

  (testing "ambiguous function column args"
    (is (= (query-result method endpoint ["extract" [["function" "count" "certname"]]])
           #{{:count 4}})))

  (testing "ambiguous group by column args"
    (is (= (query-result method endpoint ["extract" [["function" "count"] "certname"]
                                          ["group_by" "certname"]])
           #{{:certname "web2.example.com" :count 1}
             {:certname "web1.example.com" :count 1}
             {:certname "db.example.com" :count 1}
             {:certname "puppet.example.com" :count 1}}))))

(deftest-http-app paging-results
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [expected (store-example-nodes)]

    (testing "limit"
      (doseq [[limit expected] [[1 1] [2 2] [100 4]]]
        (let [results (query-result method endpoint nil {:limit limit})]
          (is (= (count results) expected)))))

    (testing "order by"
      (testing "rejects invalid fields"
        (let [{:keys [body _status]} (query-response
                                      method endpoint nil
                                      {:order_by
                                       (vector-param method
                                                     [{"field" "invalid-field"}])})]
          (is (re-find #"Unrecognized column 'invalid-field' specified in :order_by"
                       body))))

      (testing "alphabetical fields"
        (let [ordered-names ["db.example.com" "puppet.example.com"
                             "web1.example.com" "web2.example.com"]]
          (doseq [[order expected] [["asc" ordered-names]
                                    ["desc" (reverse ordered-names)]]]
            (let [result (ordered-query-result method endpoint nil
                                               {:order_by (vector-param method
                                                                        [{"field" "certname"
                                                                          "order" order}])})]
              (is (= (mapv :certname result) expected))))))

      (testing "timestamp fields"
        (let [ordered-names  ["db.example.com" "puppet.example.com"
                              "web2.example.com" "web1.example.com"]]
          (doseq [[order expected] [["asc" (reverse ordered-names)]
                                    ["desc" ordered-names]]]
            (let [result (ordered-query-result method endpoint nil
                                               {:order_by (vector-param method
                                                                        [{"field" "facts_timestamp"
                                                                          "order" order}])})]
              (is (= (mapv :certname result) expected))))))

      (testing "multiple fields"
        ;; ordered-names is in increasing timestamp order
        (let [ordered-names ["web1.example.com" "web2.example.com"
                             "puppet.example.com" "db.example.com"]]
          (doseq [[[timestamp-order name-order] expected]
                  [[["asc" "desc"] ordered-names]
                   [["asc" "asc"] ordered-names]]
                  :let [order {:order_by (vector-param method
                                                       [{"field" "facts_timestamp"
                                                         "order" timestamp-order}
                                                        {"field" "certname"
                                                         "order" name-order}])}
                        res (ordered-query-result method endpoint nil order)]]
            (is (= expected (map :certname res))))))

      (testing "offset"
        (let [ordered-names ["db.example.com" "puppet.example.com" "web1.example.com" "web2.example.com"]
              reversed-names (reverse ordered-names)]
          (doseq [[order offset expected] [["asc" 0 ordered-names]
                                           ["asc" 1 (drop 1 ordered-names)]
                                           ["asc" 2 (drop 2 ordered-names)]
                                           ["asc" 3 (drop 3 ordered-names)]
                                           ["desc" 0 reversed-names]
                                           ["desc" 1 (drop 1 reversed-names)]
                                           ["desc" 2 (drop 2 reversed-names)]
                                           ["desc" 3 (drop 3 reversed-names)]]]
            (let [result (ordered-query-result method endpoint nil
                                               {:order_by (vector-param
                                                           method
                                                           [{"field" "certname"
                                                             "order" order}])
                                                :offset offset})]
              (is (= (mapv :certname result) expected)))))))

    (doseq [[label count?] [["without" false]
                            ["with" true]]]
      (testing (str endpoint " should support paging through nodes " label " counts")
        (let [results (paged-results method
                                     {:app-fn  *app*
                                      :path    endpoint
                                      :limit   1
                                      :total   (count expected)
                                      :include_total  count?
                                      :params {:order_by
                                               (vector-param method [{:field "certname"
                                                                      :order "asc"}])}})]
          (is (= (count results) (count expected)))
          (is (= (set (vals expected))
                 (set (map :certname results)))))))))

(deftest-http-app node-timestamp-queries
  [[_version endpoint] endpoints
   method [:get :post]]

  (let [{:keys [web1 web2 db puppet]} (store-example-nodes)
        web1-catalog-ts (:catalog_timestamp (status-for-node method endpoint web1))
        web1-facts-ts (:facts_timestamp (status-for-node method endpoint web1))
        web1-report-ts (:report_timestamp (status-for-node method endpoint web1))
        is-query-result' (fn [query expected] (is-query-result method endpoint query expected))]

    (testing "basic query for timestamps"

      (is-query-result' ["=" "facts_timestamp" web1-facts-ts] [web1])
      (is-query-result' [">" "facts_timestamp" web1-facts-ts] [web2 db puppet])
      (is-query-result' [">=" "facts_timestamp" web1-facts-ts] [web1 web2 db puppet])

      (is-query-result' ["=" "catalog_timestamp" web1-catalog-ts] [web1])
      (is-query-result' [">" "catalog_timestamp" web1-catalog-ts] [db puppet])
      (is-query-result' [">=" "catalog_timestamp" web1-catalog-ts] [web1 db puppet])

      (is-query-result' ["=" "report_timestamp" web1-report-ts] [web1])
      (is-query-result' [">" "report_timestamp" web1-report-ts] [db puppet])
      (is-query-result' [">=" "report_timestamp" web1-report-ts] [web1 db puppet])))

  (let [res (->> ["<" "catalog_timestamp" "'2018-08-15 21:11:21 UTC'"]
                 (query-response method endpoint))]
    (is (= 400 (:status res)))))

(def invalid-projection-queries
  (omap/ordered-map
     ;; Top level extract using invalid fields should throw an error
     ["extract" "nothing" ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' field 'nothing'.*Acceptable fields are.*"

     ["extract" ["certname" "nothing" "nothing2"] ["~" "certname" ".*"]]
     #"Can't extract unknown 'nodes' fields 'nothing' and 'nothing2'.*Acceptable fields are.*"))

(deftest-http-app invalid-projections
  [[_version endpoint] endpoints
   method [:get :post]
   [query msg] invalid-projection-queries]
  (testing (str "query: " query " should fail with msg: " msg)
    (let [{:keys [status body headers]} (query-response method endpoint query)]
      (is (re-find msg body))
      (is (= HttpURLConnection/HTTP_BAD_REQUEST status))
      (are-error-response-headers headers))))

(def pg-versioned-invalid-regexps
  (omap/ordered-map
    "/v4/nodes" (omap/ordered-map
                  ["~" "certname" "*abc"]
                  #".*invalid regular expression: quantifier operand invalid"

                  ["~" "certname" "[]"]
                  #".*invalid regular expression: brackets.*not balanced")))

(deftest-http-app pg-invalid-regexps
  [[_version endpoint] endpoints
   method [:get :post]
   [query msg] (get pg-versioned-invalid-regexps endpoint)]
  (testing (str "query: " query " should fail with msg: " msg)
    (let [{:keys [status body]} (query-response method endpoint query)]
      (is (re-find msg body))
      (is (= HttpURLConnection/HTTP_BAD_REQUEST status)))))

(def no-parent-endpoints [[:v4 "/v4/nodes/foo/facts"]
                          [:v4 "/v4/nodes/foo/resources"]])

(deftest-http-app unknown-parent-handling
  [[_version endpoint] no-parent-endpoints
   method [:get :post]]
  (let [{:keys [status body]} (query-response method endpoint)]
    (is (= HttpURLConnection/HTTP_NOT_FOUND status))
    (is (= {:error "No information is known about node foo"} (json/parse-string body true)))))

(deftest-http-app invalid-content-type
  [[_version endpoint] endpoints]
  (store-example-nodes)
  (testing "content type other than application/json should give error"
    (let [{:keys [status body]}
          (-> (tu/query-request :post endpoint ["extract" "certname"])
              (assoc-in [:headers "content-type"] "application/x-www-form-urlencoded")
              *app*)]
      (is (= 415 status))
      (is (= "content type application/x-www-form-urlencoded not supported" body))))
  (testing "content type application/json should be accepted"
    (let [{:keys [status] :as response}
          (-> (tu/query-request :post endpoint ["extract" "certname" ["=" "certname" "puppet.example.com"]])
              (assoc-in [:headers "content-type"] "application/json")
              *app*)]
      (is (= HttpURLConnection/HTTP_OK status))
      (is (= [{:certname "puppet.example.com"}] (convert-response response))))))

(deftest-http-app error-in-query-streaming-is-communicated-to-caller
  [[_version endpoint] endpoints]
  (store-example-nodes)
  (with-redefs [munge-fn-hook (fn [_] (throw (Exception. "BOOM!")))]
    (testing "generated-stream thread exceptions make it back to the caller"
      (let [{:keys [status body]}
            (-> (tu/query-request :post endpoint ["extract" "certname" ["=" "certname" "puppet.example.com"]])
                (assoc-in [:headers "content-type"] "application/json")
                *app*)]
        (is (= 500 status))
        (is (= "BOOM!" body))))))
