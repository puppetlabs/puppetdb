(ns com.puppetlabs.puppetdb.test.http.node
  (:require [clojure.set :as set]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        ring.mock.request
        [clojure.math.combinatorics :only [combinations]]
        [clj-time.core :only [now]]
        [clj-time.coerce :only [to-timestamp]]
        com.puppetlabs.puppetdb.fixtures
        [com.puppetlabs.puppetdb.scf.storage :only [deactivate-node!]]
        [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(use-fixtures :each with-test-db with-http-app)

(def c-t "application/json")

(defn get-request
  ([path] (get-request path nil))
  ([path query]
     (let [request (if query
                     (request :get path
                              {"query" (if (string? query) query (json/generate-string query))})
                     (request :get path))
           headers (:headers request)]
       (assoc request :headers (assoc headers "Accept" c-t)))))

(defn get-response
  ([]      (get-response nil))
  ([query] (*app* (get-request "/nodes" query))))

(defn is-response-equal
  "Test if the HTTP request is a success, and if the result is equal
to the result of the form supplied to this method."
  [response body]
  (is (= 200   (:status response)))
  (is (= c-t (get-in response [:headers "Content-Type"])))
  (is (= body (if (:body response)
                (set (json/parse-string (:body response) true))
                nil)) (str response)))

(deftest test-node-handler
  (let [names     #{"node_a" "node_b" "node_c" "node_d" "node_e"}
        timestamp (to-timestamp (now))]
    (with-transacted-connection *db*
      (doseq [name names]
        (sql/insert-record :certnames {:name name})
        (sql/insert-record :certname_facts_metadata {:certname name :timestamp timestamp}))

      (deactivate-node! "node_a")
      (deactivate-node! "node_e")

      (sql/insert-records
       :certname_facts
       {:certname "node_a" :fact "kernel" :value "Linux"}
       {:certname "node_b" :fact "kernel" :value "Linux"}
       {:certname "node_b" :fact "uptime_seconds" :value "4000"}
       {:certname "node_c" :fact "kernel" :value "Darwin"}
       {:certname "node_d" :fact "uptime_seconds" :value "10000"}))

    (testing "empty query should return all nodes"
      (is-response-equal (get-response) names))

    (let [test-cases {["=" ["fact" "kernel"] "Linux"]
                      #{"node_a" "node_b"}
                      ["not" ["=" ["fact" "kernel"] "Linux"]]
                      #{"node_c" "node_d" "node_e"}
                      ["=" ["fact" "kernel"] "Nothing"]
                      #{}
                      ["=" ["fact" "uptime"] "Linux"]
                      #{}
                      ["=" ["fact" "uptime_seconds"] "10000"]
                      #{"node_d"}
                      ["<" ["fact" "uptime_seconds"] "10000.0"]
                      #{"node_b"}
                      [">=" ["fact" "uptime_seconds"] "10foobar"]
                      #{}
                      ["=" ["node" "active"] true]
                      #{"node_b" "node_c" "node_d"}}]
      (doseq [size (range 1 (inc (count test-cases)))
              terms (combinations test-cases size)
              :let [exprs      (map first terms)
                    results    (map (comp set last) terms)
                    and-expr   (cons "and" exprs)
                    and-result (apply set/intersection results)
                    or-expr    (cons "or" exprs)
                    or-result  (apply set/union results)
                    not-expr   (cons "not" exprs)
                    not-result (apply set/difference names results)]]
        (testing (str exprs " => " results)
          (is-response-equal (get-response and-expr) and-result)
          (is-response-equal (get-response or-expr) or-result)
          (is-response-equal (get-response not-expr) not-result))))))
